"""Proscenic M7 / M7 Pro local integration.

Two ways to connect, chosen in the config flow:

* phone  - talk to the Android app's HTTP bridge (the app is the robot's cloud).
* direct - Home Assistant is the robot's cloud; no phone needed.
"""

from __future__ import annotations

import logging

from homeassistant.components import network, persistent_notification
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import ConfigEntryNotReady
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .api import ProscenicApi
from .const import (
    CONF_ADVERTISE_IP,
    CONF_GATEWAY_PORT,
    CONF_HOST,
    CONF_HTTP_PORT,
    CONF_MODE,
    CONF_PORT,
    CONF_REPOINT,
    CONF_ROBOT_IP,
    CONF_ROOM_NAMES,
    DEFAULT_GATEWAY_PORT,
    DEFAULT_HTTP_PORT,
    DEFAULT_PORT,
    DOMAIN,
    MODE_DIRECT,
    MODE_PHONE,
)
from .coordinator import ProscenicCoordinator

_LOGGER = logging.getLogger(__name__)

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


def parse_room_names(text: str) -> dict[int, str]:
    """'1=Kitchen, 2=Hallway' (commas or newlines) -> {1: 'Kitchen', 2: 'Hallway'}."""
    names: dict[int, str] = {}
    for part in text.replace("\n", ",").split(","):
        key, sep, value = part.partition("=")
        if sep and key.strip().isdigit() and value.strip():
            names[int(key.strip())] = value.strip()
    return names


async def async_repoint(hass: HomeAssistant, entry: ProscenicConfigEntry) -> tuple[bool, str]:
    """Move the robot's cloud to this Home Assistant. Returns (ok, message)."""
    backend = entry.runtime_data.api
    result = await backend.repoint_robot(entry.data[CONF_ADVERTISE_IP])
    if result.success:
        return True, f"Robot {result.robot_ip} ({result.serial}) now points at {entry.data[CONF_ADVERTISE_IP]}"
    return False, result.error or "Re-pointing the robot failed"


async def _repoint_in_background(hass: HomeAssistant, entry: ProscenicConfigEntry) -> None:
    ok, message = await async_repoint(hass, entry)
    if ok:
        _LOGGER.info(message)
    else:
        persistent_notification.async_create(
            hass,
            f"Could not point the vacuum at Home Assistant: {message}\n\n"
            "Use the 'Point robot at Home Assistant' button on the device to retry.",
            title="Proscenic M7",
            notification_id=f"{DOMAIN}_repoint_{entry.entry_id}",
        )


async def async_setup_entry(hass: HomeAssistant, entry: ProscenicConfigEntry) -> bool:
    """Set up from a config entry."""
    if entry.data.get(CONF_MODE, MODE_PHONE) == MODE_DIRECT:
        from .direct.backend import DirectBackend
        from .direct.gateway import RobotGateway

        advertise = entry.data.get(CONF_ADVERTISE_IP) or await network.async_get_source_ip(hass)
        gateway = RobotGateway(
            entry.data.get(CONF_HTTP_PORT, DEFAULT_HTTP_PORT),
            entry.data.get(CONF_GATEWAY_PORT, DEFAULT_GATEWAY_PORT),
            advertise,
        )
        gateway.room_names = parse_room_names(entry.options.get(CONF_ROOM_NAMES, ""))
        try:
            await gateway.start()
        except OSError as err:
            raise ConfigEntryNotReady(
                f"Cannot listen on ports {gateway.http_port}/{gateway.gateway_port}: {err}"
            ) from err
        entry.async_on_unload(gateway.stop)
        api = DirectBackend(hass, gateway, entry.data.get(CONF_ROBOT_IP) or None)
    else:
        gateway = None
        api = ProscenicApi(
            async_get_clientsession(hass),
            entry.data[CONF_HOST],
            entry.data.get(CONF_PORT, DEFAULT_PORT),
        )

    coordinator = ProscenicCoordinator(hass, entry, api)
    await coordinator.async_config_entry_first_refresh()
    entry.runtime_data = coordinator
    if gateway is not None:
        entry.async_on_unload(gateway.add_listener(coordinator.handle_push))
    entry.async_on_unload(entry.add_update_listener(_async_reload))

    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)

    if gateway is not None and entry.data.get(CONF_REPOINT):
        # One-shot: only on first setup, never on every restart.
        hass.config_entries.async_update_entry(entry, data={**entry.data, CONF_REPOINT: False})
        entry.async_create_background_task(
            hass, _repoint_in_background(hass, entry), f"{DOMAIN}_repoint"
        )
    return True


async def _async_reload(hass: HomeAssistant, entry: ProscenicConfigEntry) -> None:
    await hass.config_entries.async_reload(entry.entry_id)


async def async_unload_entry(hass: HomeAssistant, entry: ProscenicConfigEntry) -> bool:
    """Unload a config entry."""
    return await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
