"""One-shot action buttons."""

from __future__ import annotations

from homeassistant.components.button import ButtonEntity
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import ProscenicConfigEntry
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
    async_add_entities(
        ProscenicButton(entry.runtime_data, key, action, needs_online)
        for key, action, needs_online in BUTTONS
    )


class ProscenicButton(ProscenicEntity, ButtonEntity):
    """Triggers an app command."""

    def __init__(self, coordinator, key: str, action: str, needs_online: bool) -> None:
        super().__init__(coordinator, key)
        self._attr_translation_key = key
        self._action = action
        self._requires_robot_online = needs_online

    async def async_press(self) -> None:
        await self._command(self._action)
