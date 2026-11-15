"""One-shot action buttons."""

from __future__ import annotations

from homeassistant.components.button import ButtonEntity
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import ProscenicConfigEntry, async_repoint
from .const import CONF_MODE, MODE_DIRECT
from .entity import ProscenicEntity

# (key, app command, requires robot online)
BUTTONS = (
    ("deep_clean", "deep_clean", True),
    ("empty_bin", "empty_bin", True),
    ("wake", "wake", False),  # useful precisely when the robot looks offline/asleep
)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ProscenicConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Set up buttons."""
    entities: list[ButtonEntity] = [
        ProscenicButton(entry.runtime_data, key, action, needs_online)
        for key, action, needs_online in BUTTONS
    ]
    if entry.data.get(CONF_MODE) == MODE_DIRECT:
        entities.append(RepointButton(entry.runtime_data, entry))
    async_add_entities(entities)


class ProscenicButton(ProscenicEntity, ButtonEntity):
    """Triggers an app command."""

    def __init__(self, coordinator, key: str, action: str, needs_online: bool) -> None:
        super().__init__(coordinator, key)
        self._attr_translation_key = key
        self._action = action
        self._requires_robot_online = needs_online

    async def async_press(self) -> None:
        await self._command(self._action)


class RepointButton(ProscenicEntity, ButtonEntity):
    """Direct mode: (re-)point the robot's cloud at this Home Assistant."""

    _attr_translation_key = "repoint"
    _requires_robot_online = False

    def __init__(self, coordinator, entry: ProscenicConfigEntry) -> None:
        super().__init__(coordinator, "repoint")
        self._entry = entry

    async def async_press(self) -> None:
        ok, message = await async_repoint(self.hass, self._entry)
        if not ok:
            raise HomeAssistantError(message)
