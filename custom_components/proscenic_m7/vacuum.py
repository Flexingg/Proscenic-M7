"""Vacuum entity: start/pause/stop/dock/locate/spot/suction + room & zone cleans."""

from __future__ import annotations

from typing import Any

import voluptuous as vol

from homeassistant.components.vacuum import (
    StateVacuumEntity,
    VacuumActivity,
    VacuumEntityFeature,
)
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import ServiceValidationError
from homeassistant.helpers import config_validation as cv, entity_platform
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import ProscenicConfigEntry
from .const import (
    ATTR_ROOMS,
    ATTR_ZONES,
    FAN_SPEEDS,
    SERVICE_CLEAN_ROOMS,
    SERVICE_CLEAN_ZONES,
)
from .coordinator import ProscenicCoordinator
from .entity import ProscenicEntity

_STATE_MAP = {
    "cleaning": VacuumActivity.CLEANING,
    "returning": VacuumActivity.RETURNING,
    "paused": VacuumActivity.PAUSED,
    "docked": VacuumActivity.DOCKED,
    "idle": VacuumActivity.IDLE,
}


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ProscenicConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Set up the vacuum and its entity services."""
    async_add_entities([ProscenicVacuum(entry.runtime_data)])

    platform = entity_platform.async_get_current_platform()
    platform.async_register_entity_service(
        SERVICE_CLEAN_ROOMS,
        {vol.Required(ATTR_ROOMS): vol.All(cv.ensure_list, [vol.Any(int, cv.string)])},
        "async_clean_rooms",
    )
    platform.async_register_entity_service(
        SERVICE_CLEAN_ZONES,
        {vol.Required(ATTR_ZONES): vol.All(cv.ensure_list, [vol.Coerce(int)])},
        "async_clean_zones",
    )


class ProscenicVacuum(ProscenicEntity, StateVacuumEntity):
    """The robot."""

    _attr_name = None
    _attr_fan_speed_list = FAN_SPEEDS
    _attr_supported_features = (
        VacuumEntityFeature.STATE
        | VacuumEntityFeature.START
        | VacuumEntityFeature.PAUSE
        | VacuumEntityFeature.STOP
        | VacuumEntityFeature.RETURN_HOME
        | VacuumEntityFeature.LOCATE
        | VacuumEntityFeature.CLEAN_SPOT
        | VacuumEntityFeature.FAN_SPEED
        | VacuumEntityFeature.SEND_COMMAND
    )

    def __init__(self, coordinator: ProscenicCoordinator) -> None:
        super().__init__(coordinator, "vacuum")

    @property
    def activity(self) -> VacuumActivity | None:
        if int(self._status.get("error_code") or 0) != 0:
            return VacuumActivity.ERROR
        return _STATE_MAP.get(self._status.get("state", "idle"), VacuumActivity.IDLE)

    @property
    def fan_speed(self) -> str | None:
        return self._status.get("fan_speed")

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        data = self.coordinator.data
        return {
            "rooms": {str(k): v for k, v in data.room_names.items()},
            "zones": [
                {"id": z.get("id"), "name": z.get("name"), "type": z.get("active")}
                for z in data.zones
            ],
            "raw_mode": self._status.get("raw_mode"),
            "error_code": self._status.get("error_code"),
        }

    async def async_start(self) -> None:
        # The robot reports "paused" separately; resuming is a different command.
        if self.activity is VacuumActivity.PAUSED:
            await self._command("resume")
        else:
            await self._command("start")

    async def async_pause(self) -> None:
        await self._command("pause")

    async def async_stop(self, **kwargs: Any) -> None:
        await self._command("stop")

    async def async_return_to_base(self, **kwargs: Any) -> None:
        await self._command("return_to_base")

    async def async_locate(self, **kwargs: Any) -> None:
        await self._command("locate")

    async def async_clean_spot(self, **kwargs: Any) -> None:
        await self._command("spot_clean")

    async def async_set_fan_speed(self, fan_speed: str, **kwargs: Any) -> None:
        if fan_speed not in FAN_SPEEDS:
            raise ServiceValidationError(
                f"Unknown fan speed '{fan_speed}'. Use one of: {', '.join(FAN_SPEEDS)}"
            )
        await self._command("set_fan_speed", fan_speed=fan_speed)

    async def async_send_command(
        self,
        command: str,
        params: dict[str, Any] | list[Any] | None = None,
        **kwargs: Any,
    ) -> None:
        """Raw escape hatch: any action the app's /api/command understands."""
        await self._command(command, **(params if isinstance(params, dict) else {}))

    async def async_clean_rooms(self, rooms: list[int | str]) -> None:
        """Clean rooms by id or by name (as shown in the app)."""
        names = self.coordinator.data.room_names
        by_name = {name.casefold(): rid for rid, name in names.items()}
        ids: list[int] = []
        for room in rooms:
            if isinstance(room, int) or str(room).strip().lstrip("-").isdigit():
                rid = int(room)
            else:
                rid = by_name.get(str(room).strip().casefold())
                if rid is None:
                    raise ServiceValidationError(
                        f"Unknown room '{room}'. Known rooms: "
                        + (", ".join(names.values()) or "none (no map yet)")
                    )
            if rid not in names:
                raise ServiceValidationError(f"Room id {rid} is not on the current map")
            ids.append(rid)
        await self._command("clean_rooms", rooms=ids)

    async def async_clean_zones(self, zones: list[int]) -> None:
        """Clean saved zones by id."""
        known = {
            z.get("id")
            for z in self.coordinator.data.zones
            if z.get("active") in ("normal", "depth")  # not no-go zones
        }
        missing = [z for z in zones if z not in known]
        if missing:
            raise ServiceValidationError(f"Unknown or no-go zone id(s): {missing}")
        await self._command("clean_zones", zones=zones)
