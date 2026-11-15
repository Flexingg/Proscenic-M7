"""Sensors."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from homeassistant.components.sensor import (
    SensorDeviceClass,
    SensorEntity,
    SensorEntityDescription,
    SensorStateClass,
)
from homeassistant.const import PERCENTAGE, EntityCategory, UnitOfArea, UnitOfTime
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import ProscenicConfigEntry
from .entity import ProscenicEntity


@dataclass(frozen=True, kw_only=True)
class ProscenicSensorDescription(SensorEntityDescription):
    """Sensor description with a value getter on the status dict."""

    value_fn: Callable[[dict[str, Any]], Any]


SENSORS: tuple[ProscenicSensorDescription, ...] = (
    ProscenicSensorDescription(
        key="battery",
        device_class=SensorDeviceClass.BATTERY,
        native_unit_of_measurement=PERCENTAGE,
        state_class=SensorStateClass.MEASUREMENT,
        value_fn=lambda s: s.get("battery_level"),
    ),
    ProscenicSensorDescription(
        key="clean_area",
        translation_key="clean_area",
        device_class=SensorDeviceClass.AREA,
        native_unit_of_measurement=UnitOfArea.SQUARE_METERS,
        state_class=SensorStateClass.MEASUREMENT,
        value_fn=lambda s: s.get("clean_area_m2"),
    ),
    ProscenicSensorDescription(
        key="clean_time",
        translation_key="clean_time",
        device_class=SensorDeviceClass.DURATION,
        native_unit_of_measurement=UnitOfTime.SECONDS,
        suggested_unit_of_measurement=UnitOfTime.MINUTES,
        state_class=SensorStateClass.MEASUREMENT,
        value_fn=lambda s: s.get("clean_time_sec"),
    ),
    ProscenicSensorDescription(
        key="mode",
        translation_key="mode",
        entity_category=EntityCategory.DIAGNOSTIC,
        value_fn=lambda s: s.get("raw_mode"),
    ),
    ProscenicSensorDescription(
        key="error_code",
        translation_key="error_code",
        entity_category=EntityCategory.DIAGNOSTIC,
        value_fn=lambda s: s.get("error_code", 0),
    ),
)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ProscenicConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Set up sensors."""
    async_add_entities(
        ProscenicSensor(entry.runtime_data, desc) for desc in SENSORS
    )


class ProscenicSensor(ProscenicEntity, SensorEntity):
    """A status-derived sensor."""

    entity_description: ProscenicSensorDescription

    def __init__(self, coordinator, description: ProscenicSensorDescription) -> None:
        super().__init__(coordinator, description.key)
        self.entity_description = description

    @property
    def native_value(self) -> Any:
        return self.entity_description.value_fn(self._status)
