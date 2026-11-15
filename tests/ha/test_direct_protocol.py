"""Protocol, gateway and re-home logic (no Home Assistant needed)."""

import json

import pytest

from custom_components.proscenic_m7.direct import protocol as p
from custom_components.proscenic_m7.direct.gateway import RobotGateway, RobotNotConnected
from custom_components.proscenic_m7.direct.rehome import repoint

from .fake_robot import FakeRobot, FakeUdpRobot, free_port, lz4_literals, map_upload


def test_frame_envelope():
    frame = p.start_clean("smartClean")
    assert frame.endswith("#\t#")
    obj = json.loads(frame[:-3])
    assert obj["encrypt"] == 0
    assert obj["data"]["infoType"] == 21005 and obj["data"]["data"] == {"mode": "smartClean"}
    assert obj["data"]["dInfo"]["userId"]
    # 21020 and the keepalive carry no dInfo
    assert "dInfo" not in json.loads(p.remote_control(3010)[:-3])["data"]
    pong = json.loads(p.keepalive_pong()[:-3])["data"]
    assert pong == {"infoType": 21006, "data": {"isExistConnect": True}}


def test_lz4_literals_and_matches():
    raw = bytes(range(40))
    assert p.lz4_decompress_block(lz4_literals(raw), 40) == raw
    # token 0x1_0: 1 literal 'A', then match offset 1 len 4+2 (RLE), then final literal run 'Z'
    block = bytes([0x12, ord("A"), 1, 0]) + bytes([0x10, ord("Z")])
    assert p.lz4_decompress_block(block, 100) == b"A" + b"A" * 6 + b"Z"
    with pytest.raises(ValueError):
        p.lz4_decompress_block(bytes([0x12, 65, 5, 0]), 100)  # offset beyond output


def test_snap_and_rectangle():
    assert p.snap_to_lattice_mm(0) == 25
    assert p.snap_to_lattice_mm(1234) == 1225
    assert p.snap_to_lattice_mm(-1234) == -1225
    region = p.rectangle_region(101, "Room_1", (0, 0), (10, 10))
    xs = {v[0] for v in region["vertexs"]}
    assert max(xs) - min(xs) >= 100  # minimum thickness
    assert region["mode"] == "area" and region["active"] == "normal"
    assert len(p.rectangle_region(1, "é" * 40, (0, 0), (500, 500))["name"].encode()) <= 31


def test_form_fields_with_raw_ampersand():
    fields = p.parse_form_fields('sn=A&x=1&data={"a":"b&c"}')
    assert fields["sn"] == "A" and fields["data"] == '{"a":"b&c"}'
    enc = p.parse_form_fields("sn=A&data=%7B%22a%22%3A1%7D")
    assert enc["data"] == '{"a":1}'


def test_map_and_rooms():
    m = p.parse_map_20002(map_upload())
    assert p.room_labels(m) == [1, 2]
    region = p.room_bounding_box_region(m, 1)
    assert region and region["id"] == 101
    assert p.room_bounding_box_region(m, 99) is None


def test_status_and_path_and_state():
    st = p.parse_status_20001({"mode": "sweep", "elecReal": 55, "vol": 99, "workNoisy": "max", "pos": [10, 20]}, {})
    assert st["battery"] == 55 and st["volume"] == 10 and st["suction"] == "max" and st["pos"] == (10, 20)
    assert p.api_state("sweep") == "cleaning" and p.api_state("charge") == "docked"
    assert p.api_state("findchargerpause") == "paused" and p.api_state(None) == "idle"
    assert p.parse_path_21011({"posArray": [[103, 201], [4, 8]]}) == [(100, 200), (4, 8)]
    assert p.parse_path_21011({"posArray": [103, 201, 4, 8]}) == [(100, 200), (4, 8)]


@pytest.fixture
async def gateway(socket_enabled):
    gw = RobotGateway(free_port(), free_port(), "10.0.0.5")
    await gw.start()
    yield gw
    await gw.stop()


@pytest.fixture
async def robot(gateway):
    r = FakeRobot(gateway.gateway_port, gateway.http_port)
    await r.connect()
    yield r
    await r.close()


async def test_handshake_ping_and_uploads(gateway, robot):
    # handshake -> gateway asks for status, map and zones
    await robot.wait_for(21004)
    assert {20001, 20002, 21004} <= set(robot.infos())
    assert gateway.connected and gateway.sn == "LSTEST"

    await robot.ping()
    pong = await robot.wait_for(21006)
    assert pong["data"]["isExistConnect"] is True

    assert await robot.upload(20001, {"mode": "charge", "elecReal": 80, "workNoisy": "auto"}) == 200
    assert await robot.upload(20002, map_upload()) == 200
    zones = [{"id": 3, "name": "Desk", "active": "normal", "vertexs": []}]
    assert await robot.upload(21004, {"value": zones}, path="/cleanPack/response") == 200

    status = gateway.api_status()
    assert status["state"] == "docked" and status["battery_level"] == 80 and status["online"]
    api_map = gateway.api_map()
    assert api_map["rooms"] == [1, 2] and api_map["room_names"] == {"1": "Room 1", "2": "Room 2"}
    assert api_map["zones"][0]["id"] == 3


async def test_http_registration_endpoints(gateway):
    import aiohttp

    async with aiohttp.ClientSession() as s:
        base = f"http://127.0.0.1:{gateway.http_port}"
        async with s.post(f"{base}/cleanPack/register") as r:
            assert (await r.json())["data"]["bindStatus"] == 1
        async with s.get(f"{base}/cleanPack/getSockAddr") as r:
            addr = (await r.json())["data"]["addr_list"][0]
            assert addr["port"] == gateway.gateway_port and addr["ip"] == "127.0.0.1"
        async with s.post(f"{base}/cleanPack/binding") as r:
            assert (await r.json())["code"] == 0


async def test_malformed_payloads_do_not_kill_gateway(gateway, robot):
    await robot.upload(20002, {"width": 2})  # missing fields
    await robot.upload(20001, {"elecReal": "not-a-number"})
    robot._writer.write(b"{not json}#\t#")
    await robot.ping()
    await robot.wait_for(21006)
    assert gateway.connected


async def test_send_requires_connection(gateway):
    with pytest.raises(RobotNotConnected):
        await gateway.send_frames([p.request_status()])


async def test_disconnect_detected(gateway, robot):
    await robot.wait_for(21004)
    await robot.close()
    for _ in range(100):
        if not gateway.connected:
            break
        import asyncio

        await asyncio.sleep(0.02)
    assert not gateway.connected


def test_repoint_sequence():
    fake = FakeUdpRobot()
    try:
        res = repoint("192.168.1.50", 8080, 8081, known_robot_ip="127.0.0.1", port=fake.port)
    finally:
        fake.close()
    assert res.success and res.serial == "LSTEST" and res.robot_ip == "127.0.0.1"
    cmds = [(m["cmd"], m.get("url"), m.get("ip"), m.get("port")) for m in fake.received]
    assert ("setUrl", "http://192.168.1.50:8080/", None, None) in cmds
    assert ("setUrl", None, "192.168.1.50", 8081) in cmds
    assert cmds[-1][0] == "setID" and fake.received[-1]["deviceSN"] == "LSTEST"
    assert "bindOk" not in [c[0] for c in cmds]


def test_repoint_no_robot():
    res = repoint("127.0.0.1", 8080, 8081, known_robot_ip="127.0.0.1", port=free_port())
    assert not res.success and "No robot answered" in res.error
