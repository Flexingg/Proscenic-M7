"""Tiny async client for the Proscenic M7 phone-app bridge (`:8080/api/*`)."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from typing import Any

import aiohttp

REQUEST_TIMEOUT = 8


class ProscenicApiError(Exception):
    """The phone app could not be reached or returned something unusable."""


@dataclass
class VacuumData:
    """Snapshot returned on every coordinator refresh."""

    status: dict[str, Any]
    map: dict[str, Any] = field(default_factory=dict)

    @property
    def online(self) -> bool:
        """Whether the robot itself is connected to the phone app."""
        return bool(self.status.get("online"))

    @property
    def room_names(self) -> dict[int, str]:
        """Room id -> display name."""
        raw = self.map.get("room_names") or {}
        names: dict[int, str] = {}
        for key, value in raw.items():
            try:
                names[int(key)] = str(value)
            except (TypeError, ValueError):
                continue
        # Older app builds only send ids.
        for room_id in self.map.get("rooms") or []:
            names.setdefault(int(room_id), f"Room {room_id}")
        return names

    @property
    def zones(self) -> list[dict[str, Any]]:
        """Saved zones as reported by the robot."""
        return [z for z in (self.map.get("zones") or []) if isinstance(z, dict)]


class ProscenicApi:
    """Talks to the HTTP bridge served by the Android app."""

    def __init__(self, session: aiohttp.ClientSession, host: str, port: int) -> None:
        self._session = session
        self._base = f"http://{host}:{port}"

    async def _request(self, method: str, path: str, **kwargs: Any) -> dict[str, Any]:
        try:
            async with asyncio.timeout(REQUEST_TIMEOUT):
                async with self._session.request(
                    method, f"{self._base}{path}", **kwargs
                ) as resp:
                    if resp.status != 200:
                        raise ProscenicApiError(f"HTTP {resp.status} from {path}")
                    data = await resp.json(content_type=None)
        except (aiohttp.ClientError, TimeoutError, ValueError) as err:
            raise ProscenicApiError(f"Cannot reach {self._base}{path}: {err}") from err
        if not isinstance(data, dict):
            raise ProscenicApiError(f"Unexpected response from {path}")
        return data

    async def get_status(self) -> dict[str, Any]:
        """Live robot telemetry."""
        return await self._request("GET", "/api/status")

    async def get_map(self) -> dict[str, Any]:
        """Map metadata: rooms, zones."""
        return await self._request("GET", "/api/map.json")

    async def get_data(self) -> VacuumData:
        """Fetch status and map together."""
        status = await self.get_status()
        try:
            map_data = await self.get_map()
        except ProscenicApiError:
            # Status is what matters; keep going without map info.
            map_data = {}
        return VacuumData(status=status, map=map_data)

    async def command(self, action: str, **payload: Any) -> None:
        """Send a command, e.g. command("clean_rooms", rooms=[1, 2])."""
        await self._request(
            "POST", "/api/command", json={"action": action, **payload}
        )
