"""The scheduled-clean blueprint, loaded into a real automation."""

import shutil
from datetime import timedelta
from pathlib import Path

import pytest
from homeassistant.core import HomeAssistant
from homeassistant.setup import async_setup_component
from homeassistant.util import dt as dt_util
from pytest_homeassistant_custom_component.common import async_fire_time_changed

from .test_integration import VAC, setup

BLUEPRINT = Path(__file__).parents[2] / "blueprints/automation/proscenic_m7/scheduled_clean.yaml"


async def install_blueprint(hass: HomeAssistant, inputs: dict) -> None:
    dest = Path(hass.config.path("blueprints/automation/proscenic_m7/scheduled_clean.yaml"))
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy(BLUEPRINT, dest)
    assert await async_setup_component(
        hass,
        "automation",
        {
            "automation": {
                "use_blueprint": {
                    "path": "proscenic_m7/scheduled_clean.yaml",
                    "input": {"vacuum": VAC, "time": "07:30:00", **inputs},
                }
            }
        },
    )
    await hass.async_block_till_done()


async def fire_at_0730(hass: HomeAssistant, freezer, date: str) -> None:
    freezer.move_to(f"{date} 07:30:00+00:00")
    async_fire_time_changed(hass, dt_util.utcnow() + timedelta(seconds=1))
    await hass.async_block_till_done()


@pytest.fixture(autouse=True)
async def _utc(hass: HomeAssistant):
    await hass.config.async_set_time_zone("UTC")


async def test_whole_home(hass: HomeAssistant, bridge, freezer):
    freezer.move_to("2026-11-15 07:29:00+00:00")  # a Sunday
    await setup(hass, bridge)
    await install_blueprint(hass, {"suction": "strong"})
    await fire_at_0730(hass, freezer, "2026-11-15")
    assert [c["action"] for c in bridge.state["commands"]] == ["set_fan_speed", "start"]
    assert bridge.state["commands"][0]["fan_speed"] == "strong"


async def test_rooms(hass: HomeAssistant, bridge, freezer):
    freezer.move_to("2026-11-15 07:29:00+00:00")
    await setup(hass, bridge)
    await install_blueprint(hass, {"rooms": ["Kitchen"]})
    await fire_at_0730(hass, freezer, "2026-11-15")
    assert bridge.state["commands"][-1] == {"action": "clean_rooms", "rooms": [1]}


async def test_weekday_filter(hass: HomeAssistant, bridge, freezer):
    freezer.move_to("2026-11-15 07:29:00+00:00")  # Sunday
    await setup(hass, bridge)
    await install_blueprint(hass, {"weekdays": ["mon"]})
    await fire_at_0730(hass, freezer, "2026-11-15")
    assert bridge.state["commands"] == []


async def test_skip_if_home(hass: HomeAssistant, bridge, freezer):
    freezer.move_to("2026-11-15 07:29:00+00:00")
    hass.states.async_set("person.me", "home")
    await setup(hass, bridge)
    await install_blueprint(hass, {"skip_if_home": ["person.me"]})
    await fire_at_0730(hass, freezer, "2026-11-15")
    assert bridge.state["commands"] == []
