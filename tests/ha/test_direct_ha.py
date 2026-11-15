"""Direct mode end to end: real gateway ports, fake robot, real Home Assistant."""

import socket
from unittest.mock import patch

import pytest
from homeassistant.components.vacuum import VacuumActivity
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError

from custom_components.proscenic_m7.const import DOMAIN
from custom_components.proscenic_m7.direct.rehome import RepointResult

from .fake_robot import FakeRobot, free_port, map_upload

VAC = "vacuum.proscenic_m7"


async def add_direct(hass: HomeAssistant, repoint: bool = False):
    http, gw = free_port(), free_port()
    flow = await hass.config_entries.flow.async_init(DOMAIN, context={"source": "user"})
    flow = await hass.config_entries.flow.async_configure(flow["flow_id"], {"next_step_id": "direct"})
    flow = await hass.config_entries.flow.async_configure(
        flow["flow_id"],
        {
            "advertise_ip": "192.168.1.50",
            "http_port": http,
            "gateway_port": gw,
            "robot_ip": "",
            "repoint": repoint,
        },
    )
    assert flow["type"] == "create_entry", flow
    await hass.async_block_till_done()
    return flow["result"], http, gw


@pytest.fixture
async def direct(hass: HomeAssistant, socket_enabled):
    # No rate limit in tests, so every upload is visible immediately.
    with patch("custom_components.proscenic_m7.coordinator.ProscenicCoordinator.push_interval", 0.0):
        entry, http, gw = await add_direct(hass)
        robot = FakeRobot(gw, http)
        yield hass, entry, robot
        await robot.close()
        await hass.config_entries.async_unload(entry.entry_id)
        await hass.async_block_till_done()


async def connect(hass, robot, **status):
    await robot.connect()
    await robot.wait_for(21004)
    await robot.upload(20001, {"mode": "charge", "elecReal": 77, "workNoisy": "strong", **status})
    await robot.upload(20002, map_upload())
    await hass.async_block_till_done()


async def test_entities_before_and_after_robot_connects(direct):
    hass, entry, robot = direct
    assert hass.states.get(VAC).state == "unavailable"
    assert hass.states.get("binary_sensor.proscenic_m7_robot_connected").state == "off"
    assert hass.states.get("button.proscenic_m7_point_robot_at_home_assistant") is not None

    await connect(hass, robot)
    assert hass.states.get(VAC).state == VacuumActivity.DOCKED
    assert hass.states.get("sensor.proscenic_m7_battery").state == "77"
    assert hass.states.get("select.proscenic_m7_suction").state == "strong"
    assert hass.states.get(VAC).attributes["rooms"] == {"1": "Room 1", "2": "Room 2"}


async def test_commands_reach_the_robot(direct):
    hass, entry, robot = direct
    await connect(hass, robot)
    await hass.services.async_call("vacuum", "start", {"entity_id": VAC}, blocking=True)
    frame = await robot.wait_for(21005)
    assert frame["data"] == {"mode": "smartClean"}

    robot.frames.clear()
    await hass.services.async_call(
        "vacuum", "set_fan_speed", {"entity_id": VAC, "fan_speed": "quiet"}, blocking=True
    )
    assert (await robot.wait_for(21022))["data"] == {"cmd": "quiet"}
    await hass.services.async_call("button", "press", {"entity_id": "button.proscenic_m7_empty_dust_bin"}, blocking=True)
    assert (await robot.wait_for(21024))["data"]["cmd"] == "startDustCenter"


async def test_sleeping_robot_is_woken_before_start(direct):
    hass, entry, robot = direct
    await connect(hass, robot, mode="shutdown")
    robot.frames.clear()
    await hass.services.async_call("vacuum", "start", {"entity_id": VAC}, blocking=True)
    await robot.wait_for(20001)
    assert robot.infos() == [21020, 21005, 20001]
    assert robot.frames[0]["data"]["data"] == {"ctrlCode": 4000}


async def test_resume_only_when_paused(direct):
    hass, entry, robot = direct
    await connect(hass, robot, mode="pause")
    robot.frames.clear()
    await hass.services.async_call("vacuum", "start", {"entity_id": VAC}, blocking=True)
    assert (await robot.wait_for(21017))["data"] == {"cmd": "continue"}


async def test_clean_rooms_by_name_uses_room_names_option(direct):
    hass, entry, robot = direct
    hass.config_entries.async_update_entry(entry, options={"room_names": "1=Kitchen, 2=Hallway"})
    await hass.async_block_till_done()  # options change reloads the entry
    await connect(hass, robot)
    assert hass.states.get(VAC).attributes["rooms"] == {"1": "Kitchen", "2": "Hallway"}
    robot.frames.clear()

    await hass.services.async_call(
        DOMAIN, "clean_rooms", {"entity_id": VAC, "rooms": ["kitchen"]}, blocking=True
    )
    select = await robot.wait_for(21023)
    assert select["data"]["segmentId"] == [1]
    assert select["data"]["cleanId"] == [-3]
    assert select["data"]["extraAreas"][0]["id"] == 101
    assert (await robot.wait_for(21005))["data"] == {"mode": "appointClean"}


async def test_command_without_robot_errors_instead_of_queueing(direct):
    hass, entry, robot = direct
    with pytest.raises(HomeAssistantError):
        await hass.services.async_call("button", "press", {"entity_id": "button.proscenic_m7_wake"}, blocking=True)


async def test_map_camera_renders_png(direct):
    hass, entry, robot = direct
    from homeassistant.components.camera import async_get_image

    await connect(hass, robot)
    await robot.upload(21011, {"posArray": [[0, 0], [100, 100]], "startPos": 0})
    await robot.upload(21004, {"value": [{"id": 1, "name": "z", "active": "forbid", "vertexs": [[0, 0], [100, 0], [100, 100]]}]})
    image = await async_get_image(hass, "camera.proscenic_m7_map")
    assert image.content[:4] == b"\x89PNG"


async def test_repoint_button(direct):
    hass, entry, robot = direct
    with patch(
        "custom_components.proscenic_m7.direct.backend.repoint",
        return_value=RepointResult(True, "192.168.1.9", "LSTEST"),
    ) as stub:
        await hass.services.async_call(
            "button", "press", {"entity_id": "button.proscenic_m7_point_robot_at_home_assistant"}, blocking=True
        )
    server_ip, http, gw, known = stub.call_args.args
    assert server_ip == "192.168.1.50" and http != gw and known is None

    with patch(
        "custom_components.proscenic_m7.direct.backend.repoint",
        return_value=RepointResult(False, error="No robot answered"),
    ):
        with pytest.raises(HomeAssistantError, match="No robot answered"):
            await hass.services.async_call(
                "button", "press", {"entity_id": "button.proscenic_m7_point_robot_at_home_assistant"}, blocking=True
            )


async def test_repoint_runs_once_on_setup(hass: HomeAssistant, socket_enabled):
    with patch(
        "custom_components.proscenic_m7.direct.backend.repoint",
        return_value=RepointResult(True, "192.168.1.9", "LSTEST"),
    ) as stub:
        entry, _http, _gw = await add_direct(hass, repoint=True)
        await hass.async_block_till_done()
    assert stub.call_count == 1
    assert entry.data["repoint"] is False  # one-shot: a restart must not re-point again
    await hass.config_entries.async_unload(entry.entry_id)


async def test_flow_rejects_busy_port(hass: HomeAssistant, socket_enabled):
    busy = socket.socket()
    busy.bind(("0.0.0.0", 0))
    try:
        flow = await hass.config_entries.flow.async_init(DOMAIN, context={"source": "user"})
        flow = await hass.config_entries.flow.async_configure(flow["flow_id"], {"next_step_id": "direct"})
        flow = await hass.config_entries.flow.async_configure(
            flow["flow_id"],
            {"advertise_ip": "192.168.1.50", "http_port": busy.getsockname()[1], "gateway_port": free_port(),
             "robot_ip": "", "repoint": False},
        )
        assert flow["errors"] == {"base": "port_in_use"}
    finally:
        busy.close()
