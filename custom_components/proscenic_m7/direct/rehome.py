"""Point the robot at Home Assistant using its LAN UDP :7913 control channel.

Once the robot is on your home Wi-Fi it keeps UDP 7913 open, so no pairing mode or
SoftAP is needed to move it from the phone (or the vendor cloud) to this server.
Blocking socket code: run it in an executor.

Sequence (matches the Android app's re-point, and never sends `bindOk`, which makes
the firmware fork-bomb itself):  getSn -> setUrl(url) -> setUrl(ip,port) -> getCfg -> setID.
"""

from __future__ import annotations

import json
import socket
from dataclasses import dataclass, field
from typing import Any

ROBOT_UDP_PORT = 7913
SOFTAP_IP = "192.168.78.1"
GET_CFG = '{"cmd":"getCfg"}'


@dataclass
class RepointResult:
    success: bool
    robot_ip: str | None = None
    serial: str | None = None
    error: str | None = None
    steps: list[str] = field(default_factory=list)


def _udp_request(ip: str, payload: str, timeout: float = 1.5, port: int = ROBOT_UDP_PORT) -> dict[str, Any] | None:
    """Send one datagram (retrying once) and return the JSON reply, or None."""
    data = payload.encode()
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.settimeout(timeout)
        for _ in range(2):
            try:
                sock.sendto(data, (ip, port))
                reply, _addr = sock.recvfrom(8192)
                text = reply.decode("utf-8", errors="replace").strip()
                if text:
                    parsed = json.loads(text)
                    if isinstance(parsed, dict):
                        return parsed
            except (TimeoutError, socket.timeout):
                continue
            except (OSError, ValueError):
                return None
    return None


def _subnet_prefix(ip: str) -> str:
    parts = ip.split(".")
    return ".".join(parts[:3]) if len(parts) == 4 else "192.168.1"


def discover(
    server_ip: str, known_ip: str | None = None, port: int = ROBOT_UDP_PORT
) -> tuple[str, dict[str, Any]] | None:
    """Find the robot: try a known IP first, then broadcast and sweep the /24."""
    probe = '{"cmd":"getSn"}'
    if known_ip:
        reply = _udp_request(known_ip, probe, 0.6, port)
        if reply and reply.get("sn"):
            return known_ip, reply

    prefix = _subnet_prefix(server_ip)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.settimeout(0.35)
        targets = [f"{prefix}.255", "255.255.255.255", SOFTAP_IP]
        targets += [f"{prefix}.{h}" for h in range(1, 255) if f"{prefix}.{h}" != server_ip]
        for target in targets:
            try:
                sock.sendto(probe.encode(), (target, port))
            except OSError:
                continue
        import time

        deadline = time.monotonic() + 0.9
        while time.monotonic() < deadline:
            try:
                data, addr = sock.recvfrom(4096)
            except (TimeoutError, socket.timeout):
                break
            except OSError:
                break
            try:
                reply = json.loads(data.decode("utf-8", errors="replace"))
            except ValueError:
                continue
            if isinstance(reply, dict) and reply.get("result") == "ok" and reply.get("sn"):
                return addr[0], reply
    return None


def repoint(
    server_ip: str,
    http_port: int,
    gateway_port: int,
    known_robot_ip: str | None = None,
    user_id: str = "local_user",
    port: int = ROBOT_UDP_PORT,
) -> RepointResult:
    """Tell the robot to use `server_ip` as its cloud and push gateway."""
    steps: list[str] = []
    found = discover(server_ip, known_robot_ip, port)
    if found is None:
        return RepointResult(
            False,
            error=(
                "No robot answered on UDP 7913. The robot must be on the same network as "
                "Home Assistant (host networking) and awake. If it was never paired to Wi-Fi, "
                "pair it first with the Android app."
            ),
        )
    ip, reply = found
    serial = str(reply.get("sn", "")).strip()
    steps.append(f"found robot {ip} sn={serial}")

    def require_ok(label: str, payload: dict[str, Any]) -> str | None:
        # Do not let json escape '/' - the firmware reads the URL string raw.
        res = _udp_request(ip, json.dumps(payload, separators=(",", ":")), port=port)
        steps.append(f"{label}: {res}")
        if res is None:
            return f"No reply to {label}"
        if res.get("result") != "ok":
            return f"Robot rejected {label}: {res}"
        return None

    err = (
        require_ok("setUrl(url)", {"cmd": "setUrl", "url": f"http://{server_ip}:{http_port}/"})
        or require_ok("setUrl(ip,port)", {"cmd": "setUrl", "ip": server_ip, "port": gateway_port})
    )
    if err:
        return RepointResult(False, ip, serial, err, steps)
    steps.append(f"getCfg: {_udp_request(ip, GET_CFG, port=port)}")
    err = require_ok("setID", {"cmd": "setID", "id": user_id, "deviceSN": serial})
    if err:
        return RepointResult(False, ip, serial, err, steps)
    return RepointResult(True, ip, serial, None, steps)
