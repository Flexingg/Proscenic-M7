"""A fake robot: dials the gateway like the real firmware does."""

import asyncio
import json
import socket
import threading

import aiohttp

DELIM = b"#\t#"


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def lz4_literals(raw: bytes) -> bytes:
    """Smallest valid LZ4 block: a single literal run."""
    n = len(raw)
    out = bytearray([min(n, 15) << 4])
    if n >= 15:
        rest = n - 15
        while rest >= 255:
            out.append(255)
            rest -= 255
        out.append(rest)
    return bytes(out) + raw


def map_upload(width=4, height=3, cells=None, **extra) -> dict:
    """A 20002 payload. Default grid: walls around two rooms (labels 1 and 2)."""
    import base64

    cells = cells or bytes([0, 0, 0, 0, 0, 1, 2, 0, 0, 0, 0, 0])
    assert len(cells) == width * height
    return {
        "SN": "LSTEST",
        "mapId": 5,
        "autoAreaId": 9,
        "width": width,
        "height": height,
        "resolution": 0.05,
        "x_min": -1.0,
        "y_min": -1.0,
        "map": base64.b64encode(lz4_literals(cells)).decode(),
        "chargeHandlePos": [0, 0],
        **extra,
    }


class FakeRobot:
    def __init__(self, gateway_port: int, http_port: int, sn: str = "LSTEST") -> None:
        self.gateway_port, self.http_port, self.sn = gateway_port, http_port, sn
        self.frames: list[dict] = []
        self._reader = self._writer = None
        self._task = None

    async def connect(self) -> None:
        self._reader, self._writer = await asyncio.open_connection("127.0.0.1", self.gateway_port)
        self._task = asyncio.create_task(self._read())
        await self.send_frame({"infoType": 10001, "data": {"sn": self.sn}})

    async def _read(self) -> None:
        buf = b""
        try:
            while chunk := await self._reader.read(65536):
                buf += chunk
                while (i := buf.find(DELIM)) >= 0:
                    raw, buf = buf[:i], buf[i + 3 :]
                    self.frames.append(json.loads(raw))
        except (ConnectionError, asyncio.CancelledError):
            pass

    async def send_frame(self, obj: dict) -> None:
        self._writer.write(json.dumps(obj).encode() + DELIM)
        await self._writer.drain()

    async def ping(self) -> None:
        await self.send_frame({"infoType": 21006})

    async def upload(self, info_type: int, data: dict, path="/cleanPack/uploadEvents") -> int:
        body = "sn=" + self.sn + "&data=" + json.dumps({"infoType": info_type, "data": data})
        async with aiohttp.ClientSession() as s:
            async with s.post(f"http://127.0.0.1:{self.http_port}{path}", data=body) as r:
                return r.status

    async def wait_for(self, info_type: int, timeout=3.0) -> dict:
        async def _w():
            while True:
                for f in self.frames:
                    if f.get("data", {}).get("infoType") == info_type:
                        return f["data"]
                await asyncio.sleep(0.02)

        return await asyncio.wait_for(_w(), timeout)

    def infos(self) -> list[int]:
        return [f.get("data", {}).get("infoType") for f in self.frames]

    async def close(self) -> None:
        if self._writer:
            self._writer.close()
        if self._task:
            self._task.cancel()


class FakeUdpRobot:
    """UDP :7913 control channel, mirroring docs/reverse-engineering/fakerobot.py."""

    def __init__(self, sn="LSTEST") -> None:
        self.sn = sn
        self.received: list[dict] = []
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind(("127.0.0.1", 0))
        self.sock.settimeout(0.2)
        self.port = self.sock.getsockname()[1]
        self._stop = False
        self._t = threading.Thread(target=self._run, daemon=True)
        self._t.start()

    def _run(self) -> None:
        while not self._stop:
            try:
                data, addr = self.sock.recvfrom(4096)
            except (TimeoutError, socket.timeout):
                continue
            except OSError:
                return
            msg = json.loads(data)
            self.received.append(msg)
            cmd = msg.get("cmd")
            if cmd == "getSn":
                reply = {"cmd": cmd, "result": "ok", "sn": self.sn}
            elif cmd == "setID":
                ok = msg.get("deviceSN") == self.sn
                reply = {"cmd": "getID", "result": "ok" if ok else "fail"}
            else:
                reply = {"cmd": cmd, "result": "ok"}
            self.sock.sendto(json.dumps(reply).encode(), addr)

    def close(self) -> None:
        self._stop = True
        self._t.join(1)
        self.sock.close()
