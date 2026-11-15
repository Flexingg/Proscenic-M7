"""Speaker volume."""

from __future__ import annotations

from homeassistant.components.number import NumberEntity, NumberMode
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import ProscenicConfigEntry
from .entity import ProscenicEntity


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ProscenicConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Set up the number."""
    async_add_entities([VolumeNumber(entry.runtime_data)])


class VolumeNumber(ProscenicEntity, NumberEntity):
    """Voice prompt volume, 0-10."""

    _attr_translation_key = "volume"
    _attr_entity_category = EntityCategory.CONFIG
    _attr_native_min_value = 0
    _attr_native_max_value = 10
    _attr_native_step = 1
    _attr_mode = NumberMode.SLIDER

    def __init__(self, coordinator) -> None:
        super().__init__(coordinator, "volume")

    @property
    def native_value(self) -> float | None:
        value = self._status.get("volume")
        return float(value) if value is not None else None

    async def async_set_native_value(self, value: float) -> None:
        await self._command("set_volume", value=int(value))
