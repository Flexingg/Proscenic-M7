"""Proscenic M7 / M7 Pro local integration (talks to the phone-app bridge)."""

from __future__ import annotations

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .api import ProscenicApi
from .const import CONF_HOST, CONF_PORT, DEFAULT_PORT
from .coordinator import ProscenicCoordinator

PLATFORMS = [
    "vacuum",
    "camera",
    "sensor",
    "binary_sensor",
    "button",
    "select",
    "number",
    "switch",
]

type ProscenicConfigEntry = ConfigEntry[ProscenicCoordinator]


async def async_setup_entry(hass: HomeAssistant, entry: ProscenicConfigEntry) -> bool:
    """Set up from a config entry."""
    api = ProscenicApi(
        async_get_clientsession(hass),
        entry.data[CONF_HOST],
        entry.data.get(CONF_PORT, DEFAULT_PORT),
    )
    coordinator = ProscenicCoordinator(hass, entry, api)
    await coordinator.async_config_entry_first_refresh()
    entry.runtime_data = coordinator

    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ProscenicConfigEntry) -> bool:
    """Unload a config entry."""
    return await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
