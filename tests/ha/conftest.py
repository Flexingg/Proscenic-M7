"""Fixtures: a fake phone-app bridge served over real HTTP."""

import pytest
from aiohttp import web

pytest_plugins = "pytest_homeassistant_custom_component"


@pytest.fixture(autouse=True)
def auto_enable_custom_integrations(enable_custom_integrations):
    yield


STATUS = {
    "sn": "LSLDSM7PRO20403551",
    "state": "docked",
    "raw_mode": "charge",
    "battery_level": 87,
    "fan_speed": "auto",
    "clean_area_m2": 12.5,
    "clean_time_sec": 600,
    "position_mm": [0, 0],
    "online": True,
    "sub_mode": "normal",
    "volume": 6,
    "led": True,
    "lidar_avoid": True,
    "error_code": 0,
}
MAP = {
    "has_map": True,
    "rooms": [1, 2],
    "room_names": {"1": "Kitchen", "2": "Hallway"},
    "zones": [
        {"id": 7, "name": "Under table", "active": "normal"},
        {"id": 8, "name": "Cables", "active": "forbid"},
    ],
}


@pytest.fixture(autouse=True)
def _allow_localhost(socket_enabled):
    """The fake bridge is a real localhost HTTP server."""
    yield


@pytest.fixture
async def bridge(aiohttp_server):
    """Fake bridge; `.status`, `.commands` are inspectable/mutable."""
    state = {"status": dict(STATUS), "commands": [], "down": False}

    async def status(request):
        if state["down"]:
            return web.Response(status=503)
        return web.json_response(state["status"])

    async def map_json(request):
        return web.json_response(MAP)

    async def command(request):
        state["commands"].append(await request.json())
        return web.json_response({"status": "ok"})

    app = web.Application()
    app.router.add_get("/api/status", status)
    app.router.add_get("/api/map.json", map_json)
    app.router.add_post("/api/command", command)
    server = await aiohttp_server(app)
    server.state = state
    return server
