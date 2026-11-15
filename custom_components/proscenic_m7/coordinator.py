"""Data update coordinator."""

from __future__ import annotations

import logging

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
