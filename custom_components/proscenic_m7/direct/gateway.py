"""Replacement cloud for the robot, hosted inside Home Assistant.

The robot dials two endpoints, both of which we serve:

* Channel A: HTTP (default :8080) - registration, binding, and uploads of status,
  maps and command replies.
* Channel B: raw TCP (default :8081) - `#\\t#`-delimited JSON frames. We answer every
  21006 keep-alive with `isExistConnect: true` (otherwise the robot never uploads
  maps) and push commands down the same socket.
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
from collections.abc import Callable
from typing import Any

from aiohttp import web

from . import protocol as p

_LOGGER = logging.getLogger(__name__)

DELIMITER = p.FRAME_DELIMITER.encode()
READ_TIMEOUT = 180  # the robot pings far more often than this
ACK_TYPES = {21001, 21003, 21005, 21012, 21017, 21020, 21022, 21023, 21024, 21025, 21027, 21030}


class RobotNotConnected(Exception):
    """A command was requested but the robot has no live socket to us."""


class RobotGateway:
    """Serves Channel A and B and holds the robot's latest known state."""

    def __init__(
        self,
        http_port: int = 8080,
        gateway_port: int = 8081,
        advertise_ip: str = "",
    ) -> None:
        self.http_port = http_port
        self.gateway_port = gateway_port
        self.advertise_ip = advertise_ip
        self.room_names: dict[int, str] = {}

        self.connected = False
        self.robot_addr: str | None = None
        self.sn = ""
        self.heartbeats = 0
        self.status: dict[str, Any] = {}
        self.map: dict[str, Any] | None = None
        self.zones: list[dict[str, Any]] | None = None  # from 21004; None = use map areas
        self.path: list[tuple[int, int]] = []
        self.last_status_request = 0.0
        self.map_version = 0

        self._listeners: list[Callable[[], None]] = []
        self._writer: asyncio.StreamWriter | None = None
        self._write_lock = asyncio.Lock()
        self._tcp: asyncio.Server | None = None
        self._runner: web.AppRunner | None = None
        self._tasks: set[asyncio.Task] = set()

    # ------------------------------------------------------------------ lifecycle
    def add_listener(self, cb: Callable[[], None]) -> Callable[[], None]:
        self._listeners.append(cb)
        return lambda: self._listeners.remove(cb)

    def _notify(self) -> None:
        for cb in list(self._listeners):
            try:
                cb()
            except Exception:  # noqa: BLE001 - a listener must never break the socket loop
                _LOGGER.exception("Gateway listener failed")

    async def start(self) -> None:
        """Bind both ports. Raises OSError if a port is taken."""
        self._tcp = await asyncio.start_server(self._handle_robot, "0.0.0.0", self.gateway_port)
        app = web.Application(client_max_size=32 * 1024 * 1024)
        app.router.add_route("*", "/{tail:.*}", self._handle_http)
        self._runner = web.AppRunner(app, access_log=None)
        await self._runner.setup()
        try:
            await web.TCPSite(self._runner, "0.0.0.0", self.http_port).start()
        except OSError:
            await self.stop()
            raise
        _LOGGER.info("Proscenic gateway listening on :%s (HTTP) and :%s (TCP)", self.http_port, self.gateway_port)

    async def stop(self) -> None:
        if self._writer is not None:
            self._writer.close()
            self._writer = None
        if self._tcp is not None:
            self._tcp.close()
            await self._tcp.wait_closed()
            self._tcp = None
        if self._runner is not None:
            await self._runner.cleanup()
            self._runner = None
        for task in list(self._tasks):
            task.cancel()
        self.connected = False

    def _spawn(self, coro) -> None:
        task = asyncio.get_running_loop().create_task(coro)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    # ------------------------------------------------------------------ commands
    async def send_frames(self, frames: list[str]) -> None:
        """Push frames to the robot. Never queued: a stale 'start' replayed later is worse than an error."""
        writer = self._writer
        if writer is None or not self.connected:
            raise RobotNotConnected("The robot is not connected to Home Assistant")
        async with self._write_lock:
            for frame in frames:
                writer.write(frame.encode())
            await writer.drain()

    # ------------------------------------------------------------------ Channel B (TCP)
    async def _handle_robot(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        old = self._writer
        self._writer = writer
        if old is not None and old is not writer:
            old.close()
        peer = writer.get_extra_info("peername")
        self.robot_addr = f"{peer[0]}:{peer[1]}" if peer else None
        self.connected = True
        _LOGGER.info("Robot connected on gateway from %s", self.robot_addr)
        self._notify()
        buf = b""
        try:
            while True:
                chunk = await asyncio.wait_for(reader.read(16384), READ_TIMEOUT)
                if not chunk:
                    break
                buf += chunk
                while (idx := buf.find(DELIMITER)) >= 0:
                    raw, buf = buf[:idx].strip(), buf[idx + len(DELIMITER) :]
                    if raw:
                        await self._handle_frame(raw, writer)
        except (TimeoutError, ConnectionError, OSError):
            pass
        except Exception:  # noqa: BLE001
            _LOGGER.exception("Error handling robot socket")
        finally:
            if self._writer is writer:
                self._writer = None
                self.connected = False
                self.robot_addr = None
                _LOGGER.info("Robot disconnected from gateway")
                self._notify()
            writer.close()

    async def _handle_frame(self, raw: bytes, writer: asyncio.StreamWriter) -> None:
        try:
            obj = json.loads(raw.decode("utf-8", errors="replace"))
        except ValueError:
            return
        if not isinstance(obj, dict):
            return
        info_type = obj.get("infoType", -1)
        if info_type == 10001:
            data = obj.get("data") or {}
            sn = data.get("sn", "") if isinstance(data, dict) else ""
            if sn:
                self.sn = sn
            await self.send_frames(
                [p.request_status(), p.request_map(), p.get_area_tactics()]
            )
            self.last_status_request = time.monotonic()
            self._notify()
        elif info_type == 21006:
            self.heartbeats += 1
            async with self._write_lock:
                writer.write(p.keepalive_pong().encode())
                await writer.drain()
        else:
            inner = obj.get("data")
            self._dispatch(info_type, inner if isinstance(inner, dict) else obj)

    # ------------------------------------------------------------------ Channel A (HTTP)
    async def _handle_http(self, request: web.Request) -> web.Response:
        path = request.path
        body = (await request.read()).decode("utf-8", errors="replace")
        ok = {"code": 0, "message": "ok", "data": {}}

        if path.endswith("/cleanPack/register"):
            payload: dict[str, Any] = {
                "code": 0,
                "message": "ok",
                "data": {
                    "bindStatus": 1,
                    "session": "0123456789abcdef",
                    "cookies": "local_sid_0123456789abcdef012345",
                },
            }
        elif path.endswith("/cleanPack/getSockAddr"):
            sock = request.transport.get_extra_info("sockname") if request.transport else None
            ip = sock[0] if sock and sock[0] not in ("0.0.0.0", "::") else self.advertise_ip
            payload = {
                "code": 0,
                "message": "success",
                "data": {"addr_list": [{"ip": ip, "port": self.gateway_port}]},
            }
        elif path.endswith(("/cleanPack/uploadEvents", "/cleanPack/response")):
            self._handle_upload(body)
            payload = ok
        else:
            payload = ok  # binding, unbinding, sync, uploadSingle, uploadLogs, ...
        return web.json_response(payload)

    def _handle_upload(self, body: str) -> None:
        params = p.parse_form_fields(body)
        raw = params.get("data")
        if not raw:
            return
        try:
            outer = json.loads(raw)
        except ValueError:
            return
        if not isinstance(outer, dict):
            return
        try:
            info_type = int(outer.get("infoType", params.get("infoType", -1)))
        except (TypeError, ValueError):
            info_type = -1
        inner = outer.get("data")
        self._dispatch(info_type, inner if isinstance(inner, dict) else outer)

    # ------------------------------------------------------------------ dispatch
    def _dispatch(self, info_type: int, data: dict[str, Any]) -> None:
        try:
            if info_type == 20001:
                self.status = p.parse_status_20001(data, self.status)
            elif info_type == 20002:
                self.map = p.parse_map_20002(data)
                self.map_version += 1
            elif info_type == 21011:
                pts = p.parse_path_21011(data)
                start = int(data.get("startPos", 0) or 0)
                self.path = self.path[:start] + pts if start > 0 else pts
            elif info_type == 21004:
                arr = data.get("value")
                self.zones = [
                    {**z, "id": z.get("id", i)}
                    for i, z in enumerate(arr if isinstance(arr, list) else [], start=1)
                    if isinstance(z, dict)
                ]
            elif info_type in ACK_TYPES:
                self._spawn(self._after_ack(info_type))
        except Exception:  # noqa: BLE001 - malformed robot payload must not kill the connection
            _LOGGER.debug("Ignoring malformed infoType %s payload", info_type, exc_info=True)
            return
        self._notify()

    async def _after_ack(self, info_type: int) -> None:
        try:
            await self.send_frames([p.request_status()])
            if info_type == 21030:  # room segmentation edit -> fresh map
                await asyncio.sleep(1.2)
                await self.send_frames([p.request_map()])
        except RobotNotConnected:
            pass

    # ------------------------------------------------------------------ views (same shape as the phone bridge)
    def current_zones(self) -> list[dict[str, Any]]:
        if self.zones is not None:
            return self.zones
        return list(self.map["areas"]) if self.map else []

    def api_status(self) -> dict[str, Any]:
        s = self.status
        errors = s.get("errors") or []
        pos = s.get("pos") or (0, 0)
        return {
            "sn": self.sn,
            "state": p.api_state(s.get("mode")),
            "raw_mode": s.get("mode"),
            "battery_level": s.get("battery"),
            "fan_speed": s.get("suction"),
            "clean_area_m2": s.get("clean_area"),
            "clean_time_sec": s.get("clean_time"),
            "position_mm": list(pos),
            "online": self.connected,
            "sub_mode": s.get("sub_mode"),
            "volume": s.get("volume"),
            "led": s.get("led"),
            "lidar_avoid": s.get("lidar_avoid"),
            "error_code": errors[0] if errors else 0,
            "heading_mrad": s.get("phi", 0),
            "last_updated_ms": int(s.get("updated", 0) * 1000),
        }

    def api_map(self) -> dict[str, Any]:
        if not self.map:
            return {"has_map": False}
        labels = p.room_labels(self.map)
        return {
            "has_map": True,
            "rooms": labels,
            "room_names": {str(r): self.room_names.get(r, f"Room {r}") for r in labels},
            "zones": self.current_zones(),
        }
