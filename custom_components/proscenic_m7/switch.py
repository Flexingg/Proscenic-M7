"""Robot settings switches."""

from __future__ import annotations

from homeassistant.components.switch import SwitchEntity
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import ProscenicConfigEntry
from .entity import ProscenicEntity

# (key, status field, app command)
SWITCHES = (
    ("led", "led", "set_led"),
    ("lidar_avoid", "lidar_avoid", "set_lidar_avoid"),
)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ProscenicConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Set up switches."""
    async_add_entities(
        ProscenicSwitch(entry.runtime_data, *spec) for spec in SWITCHES
    )


class ProscenicSwitch(ProscenicEntity, SwitchEntity):
    """A boolean robot setting."""

    _attr_entity_category = EntityCategory.CONFIG

    def __init__(self, coordinator, key: str, field: str, action: str) -> None:
        super().__init__(coordinator, key)
        self._attr_translation_key = key
        self._field = field
        self._action = action

    @property
    def is_on(self) -> bool | None:
        value = self._status.get(self._field)
        return bool(value) if value is not None else None

    async def async_turn_on(self, **kwargs) -> None:
        await self._command(self._action, value=True)

    async def async_turn_off(self, **kwargs) -> None:
        await self._command(self._action, value=False)
