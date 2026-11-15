"""Shared entity base."""

from __future__ import annotations

from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.update_coordinator import CoordinatorEntity

from .const import DOMAIN
from .coordinator import ProscenicCoordinator


class ProscenicEntity(CoordinatorEntity[ProscenicCoordinator]):
    """Base class: one device per robot, unavailable while the robot is offline."""

    _attr_has_entity_name = True
    # Entities that stay available while the robot is offline set this False.
    _requires_robot_online = True

    def __init__(self, coordinator: ProscenicCoordinator, key: str) -> None:
        super().__init__(coordinator)
        self._key = key
        entry = coordinator.config_entry
        self._attr_unique_id = f"{entry.unique_id}_{key}"
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, entry.unique_id)},
            manufacturer="Proscenic",
            model="M7 / M7 Pro",
            name=entry.title,
            serial_number=(coordinator.data.status.get("sn") or None)
            if coordinator.data
            else None,
        )

    @property
    def _status(self) -> dict:
        return self.coordinator.data.status if self.coordinator.data else {}

    @property
    def available(self) -> bool:
        if not super().available:
            return False
        if self._requires_robot_online:
            return self.coordinator.data.online
        return True

    async def _command(self, action: str, **payload) -> None:
        await self.coordinator.async_command(action, **payload)
