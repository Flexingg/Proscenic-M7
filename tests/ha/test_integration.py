"""End-to-end tests against the fake bridge."""

import pytest
from homeassistant.components.vacuum import VacuumActivity
from homeassistant.config_entries import ConfigEntryState
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError, ServiceValidationError
from homeassistant.helpers import entity_registry as er
from pytest_homeassistant_custom_component.common import MockConfigEntry

from custom_components.proscenic_m7.const import DOMAIN

VAC = "vacuum.proscenic_m7"


async def setup(hass: HomeAssistant, bridge) -> MockConfigEntry:
    entry = MockConfigEntry(
        domain=DOMAIN,
        unique_id="LSLDSM7PRO20403551",
        title="Proscenic M7",
        data={"host": bridge.host, "port": bridge.port},
    )
    entry.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done()
    return entry


async def test_config_flow(hass: HomeAssistant, bridge):
    result = await hass.config_entries.flow.async_init(DOMAIN, context={"source": "user"})
    result = await hass.config_entries.flow.async_configure(
        result["flow_id"], {"host": bridge.host, "port": bridge.port}
    )
    assert result["type"] == "create_entry"
    assert result["result"].unique_id == "LSLDSM7PRO20403551"


async def test_config_flow_cannot_connect(hass: HomeAssistant, bridge):
    bridge.state["down"] = True
    result = await hass.config_entries.flow.async_init(DOMAIN, context={"source": "user"})
    result = await hass.config_entries.flow.async_configure(
        result["flow_id"], {"host": bridge.host, "port": bridge.port}
    )
    assert result["errors"] == {"base": "cannot_connect"}


async def test_states_and_entities(hass: HomeAssistant, bridge):
    entry = await setup(hass, bridge)
    assert entry.state is ConfigEntryState.LOADED
    s = hass.states.get(VAC)
    assert s.state == VacuumActivity.DOCKED
    assert s.attributes["fan_speed"] == "auto"
    assert s.attributes["rooms"] == {"1": "Kitchen", "2": "Hallway"}
    ids = {e.entity_id for e in er.async_entries_for_config_entry(er.async_get(hass), entry.entry_id)}
    # vacuum + camera + 5 sensors + 3 binary sensors + 3 buttons + select + number + 2 switches
    assert len(ids) == 17, sorted(ids)
    assert hass.states.get("sensor.proscenic_m7_battery").state == "87"
    assert hass.states.get("binary_sensor.proscenic_m7_charging").state == "on"
    assert hass.states.get("number.proscenic_m7_volume").state == "6.0"
    assert hass.states.get("select.proscenic_m7_suction").state == "auto"
    assert hass.states.get("switch.proscenic_m7_led").state == "on"


async def test_error_state(hass: HomeAssistant, bridge):
    bridge.state["status"].update(error_code=3, state="idle")
    await setup(hass, bridge)
    assert hass.states.get(VAC).state == VacuumActivity.ERROR
    assert hass.states.get("binary_sensor.proscenic_m7_problem").state == "on"


async def test_robot_offline_makes_robot_entities_unavailable(hass: HomeAssistant, bridge):
    bridge.state["status"].update(online=False)
    await setup(hass, bridge)
    assert hass.states.get(VAC).state == "unavailable"
    assert hass.states.get("binary_sensor.proscenic_m7_robot_connected").state == "off"
    assert hass.states.get("button.proscenic_m7_wake").state != "unavailable"


async def call(hass, domain, service, data):
    await hass.services.async_call(domain, service, data, blocking=True)


async def test_basic_commands(hass: HomeAssistant, bridge):
    await setup(hass, bridge)
    cmds = bridge.state["commands"]
    await call(hass, "vacuum", "start", {"entity_id": VAC})
    await call(hass, "vacuum", "pause", {"entity_id": VAC})
    await call(hass, "vacuum", "stop", {"entity_id": VAC})
    await call(hass, "vacuum", "return_to_base", {"entity_id": VAC})
    await call(hass, "vacuum", "locate", {"entity_id": VAC})
    await call(hass, "vacuum", "clean_spot", {"entity_id": VAC})
    await call(hass, "vacuum", "set_fan_speed", {"entity_id": VAC, "fan_speed": "max"})
    assert [c["action"] for c in cmds] == [
        "start", "pause", "stop", "return_to_base", "locate", "spot_clean", "set_fan_speed",
    ]
    assert cmds[-1]["fan_speed"] == "max"


async def test_start_resumes_when_paused(hass: HomeAssistant, bridge):
    bridge.state["status"].update(state="paused", raw_mode="pause")
    await setup(hass, bridge)
    await call(hass, "vacuum", "start", {"entity_id": VAC})
    assert bridge.state["commands"][-1]["action"] == "resume"


async def test_clean_rooms_by_name_and_id(hass: HomeAssistant, bridge):
    await setup(hass, bridge)
    await call(hass, DOMAIN, "clean_rooms", {"entity_id": VAC, "rooms": ["kitchen", 2]})
    assert bridge.state["commands"][-1] == {"action": "clean_rooms", "rooms": [1, 2]}
    with pytest.raises(ServiceValidationError):
        await call(hass, DOMAIN, "clean_rooms", {"entity_id": VAC, "rooms": ["Garage"]})
    with pytest.raises(ServiceValidationError):
        await call(hass, DOMAIN, "clean_rooms", {"entity_id": VAC, "rooms": [99]})


async def test_clean_zones_rejects_nogo(hass: HomeAssistant, bridge):
    await setup(hass, bridge)
    await call(hass, DOMAIN, "clean_zones", {"entity_id": VAC, "zones": [7]})
    assert bridge.state["commands"][-1] == {"action": "clean_zones", "zones": [7]}
    with pytest.raises(ServiceValidationError):
        await call(hass, DOMAIN, "clean_zones", {"entity_id": VAC, "zones": [8]})


async def test_helper_entities(hass: HomeAssistant, bridge):
    await setup(hass, bridge)
    cmds = bridge.state["commands"]
    await call(hass, "button", "press", {"entity_id": "button.proscenic_m7_deep_clean"})
    await call(hass, "button", "press", {"entity_id": "button.proscenic_m7_empty_dust_bin"})
    await call(hass, "number", "set_value", {"entity_id": "number.proscenic_m7_volume", "value": 3})
    await call(hass, "select", "select_option", {"entity_id": "select.proscenic_m7_suction", "option": "quiet"})
    await call(hass, "switch", "turn_off", {"entity_id": "switch.proscenic_m7_led"})
    assert cmds == [
        {"action": "deep_clean"},
        {"action": "empty_bin"},
        {"action": "set_volume", "value": 3},
        {"action": "set_fan_speed", "fan_speed": "quiet"},
        {"action": "set_led", "value": False},
    ]


async def test_command_failure_raises(hass: HomeAssistant, bridge):
    await setup(hass, bridge)
    await bridge.close()
    with pytest.raises(HomeAssistantError):
        await call(hass, "vacuum", "start", {"entity_id": VAC})


async def test_map_camera(hass: HomeAssistant, bridge):
    from homeassistant.components.camera import async_get_image

    await setup(hass, bridge)
    image = await async_get_image(hass, "camera.proscenic_m7_map")
    assert image.content[:4] == b"\x89PNG"


async def test_map_camera_no_map(hass: HomeAssistant, bridge):
    bridge.state["png"] = None
    await setup(hass, bridge)
    from homeassistant.components.camera import async_get_image

    with pytest.raises(HomeAssistantError):
        await async_get_image(hass, "camera.proscenic_m7_map")
