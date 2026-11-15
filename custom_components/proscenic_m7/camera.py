"""Floor map as a camera image (use it in a picture-entity card)."""

from __future__ import annotations

from homeassistant.components.camera import Camera
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import ProscenicConfigEntry
from .api import ProscenicApiError
from .coordinator import ProscenicCoordinator
from .entity import ProscenicEntity


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ProscenicConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Set up the map camera."""
    async_add_entities([ProscenicMapCamera(entry.runtime_data)])


class ProscenicMapCamera(ProscenicEntity, Camera):
    """Rendered floor map with rooms, zones, cleaning path, dock and robot."""

    _attr_translation_key = "map"
    content_type = "image/png"
    # The map is still worth showing from the phone's cache when the robot is asleep.
    _requires_robot_online = False

    def __init__(self, coordinator: ProscenicCoordinator) -> None:
        ProscenicEntity.__init__(self, coordinator, "map")
        Camera.__init__(self)

    @property
    def available(self) -> bool:
        return super().available and bool(self.coordinator.data.map.get("has_map"))

    async def async_camera_image(
        self, width: int | None = None, height: int | None = None
    ) -> bytes | None:
        try:
            return await self.coordinator.api.get_map_image()
        except ProscenicApiError:
            return None
