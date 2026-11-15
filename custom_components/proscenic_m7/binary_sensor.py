"""Binary sensors."""

from __future__ import annotations

from homeassistant.components.binary_sensor import (
    BinarySensorDeviceClass,
    BinarySensorEntity,
)
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
    """Set up binary sensors."""
    c = entry.runtime_data
    async_add_entities([RobotConnected(c), Charging(c), Problem(c)])


class RobotConnected(ProscenicEntity, BinarySensorEntity):
    """Is the robot connected to the phone app? Stays available when it is not."""

    _attr_device_class = BinarySensorDeviceClass.CONNECTIVITY
    _attr_entity_category = EntityCategory.DIAGNOSTIC
    _attr_translation_key = "robot_connected"
    _requires_robot_online = False

    def __init__(self, coordinator) -> None:
        super().__init__(coordinator, "robot_connected")

    @property
    def is_on(self) -> bool:
        return self.coordinator.data.online


class Charging(ProscenicEntity, BinarySensorEntity):
    """On the dock and charging."""

    _attr_device_class = BinarySensorDeviceClass.BATTERY_CHARGING

    def __init__(self, coordinator) -> None:
        super().__init__(coordinator, "charging")

    @property
    def is_on(self) -> bool:
        return self._status.get("raw_mode") in ("charge", "fullcharge")


class Problem(ProscenicEntity, BinarySensorEntity):
    """Robot reports an error code."""

    _attr_device_class = BinarySensorDeviceClass.PROBLEM

    def __init__(self, coordinator) -> None:
        super().__init__(coordinator, "problem")

    @property
    def is_on(self) -> bool:
        return int(self._status.get("error_code") or 0) != 0
