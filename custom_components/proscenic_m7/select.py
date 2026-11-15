"""Suction power select."""

from __future__ import annotations

from homeassistant.components.select import SelectEntity
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import ProscenicConfigEntry
from .const import FAN_SPEEDS
from .entity import ProscenicEntity


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ProscenicConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Set up the select."""
    async_add_entities([SuctionSelect(entry.runtime_data)])


class SuctionSelect(ProscenicEntity, SelectEntity):
    """Suction power."""

    _attr_translation_key = "suction"
    _attr_options = FAN_SPEEDS

    def __init__(self, coordinator) -> None:
        super().__init__(coordinator, "suction")

    @property
    def current_option(self) -> str | None:
        value = self._status.get("fan_speed")
        return value if value in FAN_SPEEDS else None

    async def async_select_option(self, option: str) -> None:
        await self._command("set_fan_speed", fan_speed=option)
