"""Direct backend: Home Assistant is the robot's cloud. Same interface as ProscenicApi."""

from __future__ import annotations

import asyncio
import time
from typing import Any

from homeassistant.core import HomeAssistant

from ..api import ProscenicApiError, VacuumData
from . import protocol as p
from .gateway import RobotGateway, RobotNotConnected
from .render import render_map_png
from .rehome import RepointResult, repoint

SLEEP_MODES = ("shutdown", "dormant")
STATUS_REFRESH_SECONDS = 30
MAP_CACHE_SECONDS = 3


class DirectBackend:
    """Translates the integration's actions into robot frames and back."""

    def __init__(self, hass: HomeAssistant, gateway: RobotGateway, robot_ip: str | None = None) -> None:
        self._hass = hass
        self.gateway = gateway
        self.robot_ip = robot_ip
        self._map_cache: tuple[float, int, bytes] | None = None

    # ------------------------------------------------------------------ data
    async def get_data(self) -> VacuumData:
        g = self.gateway
        # The robot pushes telemetry on its own, but ask now and then so a quiet robot stays fresh.
        if g.connected and time.monotonic() - g.last_status_request > STATUS_REFRESH_SECONDS:
            g.last_status_request = time.monotonic()
            try:
                await g.send_frames([p.request_status()])
            except RobotNotConnected:
                pass
        return self.snapshot()

    def snapshot(self) -> VacuumData:
        return VacuumData(status=self.gateway.api_status(), map=self.gateway.api_map())

    async def get_map_image(self) -> bytes | None:
        g = self.gateway
        if not g.map:
            return None
        now = time.monotonic()
        if self._map_cache and self._map_cache[1] == g.map_version and now - self._map_cache[0] < MAP_CACHE_SECONDS:
            return self._map_cache[2]
        names = {r: g.room_names.get(r, f"Room {r}") for r in p.room_labels(g.map)}
        png = await self._hass.async_add_executor_job(
            render_map_png,
            g.map,
            g.current_zones(),
            list(g.path),
            g.status.get("pos"),
            g.status.get("phi", 0),
            names,
        )
        self._map_cache = (now, g.map_version, png)
        return png

    # ------------------------------------------------------------------ commands
    async def command(self, action: str, **payload: Any) -> None:
        g = self.gateway
        mode = g.status.get("mode")
        paused = mode in ("pause", "findchargerpause")
        asleep = mode in SLEEP_MODES
        action = action.lower()

        def start(frame: str) -> list[str]:
            # A sleeping robot ignores motor commands until woken.
            return [p.remote_control(4000), frame, p.request_status()] if asleep else [frame]

        frames: list[str]
        if action in ("start", "clean", "turn_on"):
            frames = start(p.start_clean("smartClean"))
        elif action == "deep_clean":
            frames = start(p.start_clean("depthTotalClean"))
        elif action == "pause":
            if paused:
                return
            frames = [p.pause_continue_stop("pause")]
        elif action in ("resume", "continue"):
            if not paused:
                return
            frames = [p.pause_continue_stop("continue")]
        elif action == "play_pause":
            frames = [p.pause_continue_stop("continue" if paused else "pause")]
        elif action in ("stop", "turn_off"):
            frames = [p.pause_continue_stop("stop")]
        elif action in ("return_to_base", "dock", "charge"):
            frames = [p.return_to_dock()]
        elif action == "locate":
            frames = [p.remote_control(3010)]
        elif action in ("empty_bin", "collect_dust"):
            frames = [p.start_dust_collection()]
        elif action in ("spot_clean", "clean_spot"):
            frames = [p.remote_control(3001)]
        elif action == "wake":
            frames = [p.remote_control(4000), p.remote_control(3002), p.request_status()]
        elif action == "set_fan_speed":
            level = payload.get("fan_speed", "auto")
            frames = [p.set_suction(level if level in p.SUCTION_LEVELS else "auto")]
        elif action == "set_volume":
            frames = [p.set_volume(int(payload.get("value", payload.get("volume", 5))))]
        elif action == "set_led":
            frames = [p.set_led(bool(payload.get("value", payload.get("enabled", True))))]
        elif action == "set_lidar_avoid":
            frames = [p.set_lidar_collision(bool(payload.get("value", payload.get("enabled", True))))]
        elif action == "clean_rooms":
            frames = self._room_clean_frames([int(r) for r in payload.get("rooms", [])])
        elif action == "clean_zones":
            frames = p.zone_or_room_clean_sequence(stored_zone_ids=[int(z) for z in payload.get("zones", [])])
        else:
            raise ProscenicApiError(f"Unknown command '{action}'")

        try:
            await g.send_frames(frames)
        except RobotNotConnected as err:
            raise ProscenicApiError(str(err)) from err

    def _room_clean_frames(self, rooms: list[int]) -> list[str]:
        g = self.gateway
        if not rooms:
            return [p.start_clean("smartClean")]
        if not g.map:
            raise ProscenicApiError("No map yet, so rooms cannot be cleaned")
        suction = g.status.get("suction", "auto")
        extras = [
            region
            for label in rooms
            if (region := p.room_bounding_box_region(g.map, label, suction)) is not None
        ]
        return p.zone_or_room_clean_sequence(extra_areas=extras, segment_room_labels=rooms)

    # ------------------------------------------------------------------ re-homing
    async def repoint_robot(self, server_ip: str) -> RepointResult:
        """Point the robot's cloud at this Home Assistant instance (blocking work in an executor)."""
        g = self.gateway
        known = self.robot_ip or g.robot_addr and g.robot_addr.rsplit(":", 1)[0] or None
        return await self._hass.async_add_executor_job(
            repoint, server_ip, g.http_port, g.gateway_port, known
        )
