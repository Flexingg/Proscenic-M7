"""Data update coordinator."""

from __future__ import annotations

import asyncio
import logging
import time

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.update_coordinator import DataUpdateCoordinator, UpdateFailed

from .api import ProscenicApi, ProscenicApiError, VacuumData
from .const import DOMAIN, SCAN_INTERVAL

_LOGGER = logging.getLogger(__name__)


class ProscenicCoordinator(DataUpdateCoordinator[VacuumData]):
    """Polls the phone app for robot status."""

    config_entry: ConfigEntry

    def __init__(
        self, hass: HomeAssistant, entry: ConfigEntry, api: ProscenicApi
    ) -> None:
        super().__init__(
            hass,
            _LOGGER,
            config_entry=entry,
            name=DOMAIN,
            update_interval=SCAN_INTERVAL,
        )
        self.api = api
        self._last_push = 0.0
        self._last_online: bool | None = None
        self._pending: asyncio.TimerHandle | None = None

    async def _async_update_data(self) -> VacuumData:
        try:
            return await self.api.get_data()
        except ProscenicApiError as err:
            raise UpdateFailed(str(err)) from err

    async def async_command(self, action: str, **payload) -> None:
        """Send a command, then refresh shortly after."""
        try:
            await self.api.command(action, **payload)
        except ProscenicApiError as err:
            raise HomeAssistantError(f"Proscenic command '{action}' failed: {err}") from err
        await self.async_request_refresh()

    push_interval = 1.0  # seconds; robot telemetry can arrive faster than HA needs it

    def handle_push(self) -> None:
        """Direct mode: the gateway has new robot data.

        Rate-limited, but never drops the last update (a trailing push is scheduled),
        and connect/disconnect changes go out immediately.
        """
        online = self.api.snapshot().online
        elapsed = time.monotonic() - self._last_push
        if online != self._last_online or elapsed >= self.push_interval:
            self._push_now()
        elif self._pending is None:
            self._pending = self.hass.loop.call_later(self.push_interval - elapsed, self._flush)

    def _flush(self) -> None:
        self._pending = None
        self._push_now()

    def _push_now(self) -> None:
        if self._pending is not None:
            self._pending.cancel()
            self._pending = None
        data = self.api.snapshot()
        self._last_online = data.online
        self._last_push = time.monotonic()
        self.async_set_updated_data(data)

    async def async_shutdown(self) -> None:
        if self._pending is not None:
            self._pending.cancel()
            self._pending = None
        await super().async_shutdown()
