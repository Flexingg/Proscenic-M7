"""Wire protocol for the Proscenic M7 (LS_S6): frame builders and payload parsers.

A Python port of the Android app's ProscenicProtocol.kt / Lz4Codec.kt, verified
against the same robot. Channel B is raw TCP with `#\\t#`-delimited JSON frames.
"""

from __future__ import annotations

import base64
import json
import time
from typing import Any

FRAME_DELIMITER = "#\t#"
# Same value the Android app uses; the robot does not validate it.
DEFAULT_USER_ID = "local_android"

SUCTION_LEVELS = ("quiet", "auto", "strong", "max")


# --------------------------------------------------------------------------
# Frame builders (robot-bound)
# --------------------------------------------------------------------------
def build_frame(
    info_type: int,
    data: dict[str, Any] | None = None,
    *,
    include_dinfo: bool = True,
) -> str:
    """Wrap a message in the double-nested, unencrypted Channel-B envelope."""
    inner: dict[str, Any] = {"infoType": info_type, "data": data or {}}
    if include_dinfo:
        inner["dInfo"] = {"ts": str(int(time.time() * 1000)), "userId": DEFAULT_USER_ID}
    return json.dumps({"encrypt": 0, "data": inner}, separators=(",", ":")) + FRAME_DELIMITER


def keepalive_pong() -> str:
    """Reply to a 21006 ping. Without isExistConnect the robot never uploads maps."""
    return build_frame(21006, {"isExistConnect": True}, include_dinfo=False)


def start_clean(mode: str) -> str:
    """21005: smartClean | depthTotalClean | appointClean."""
    return build_frame(21005, {"mode": mode})


def pause_continue_stop(cmd: str) -> str:
    if cmd not in ("pause", "continue", "stop"):
        raise ValueError(cmd)
    return build_frame(21017, {"cmd": cmd})


def return_to_dock() -> str:
    return build_frame(21012, {"cmd": "start"})


def set_suction(level: str) -> str:
    if level not in SUCTION_LEVELS:
        raise ValueError(level)
    return build_frame(21022, {"cmd": level})


def set_volume(volume: int) -> str:
    return build_frame(21024, {"cmd": "setVolume", "value": max(0, min(10, int(volume)))})


def set_led(enabled: bool) -> str:
    return build_frame(21024, {"cmd": "setledswitch", "value": 1 if enabled else 0})


def set_lidar_collision(enabled: bool) -> str:
    return build_frame(21024, {"cmd": "setLidarCollision", "value": 1 if enabled else 0})


def start_dust_collection() -> str:
    return build_frame(21024, {"cmd": "startDustCenter", "value": 0})


def remote_control(ctrl_code: int) -> str:
    """21020. 3001 spot clean, 3010 locate (beep), 4000 exit manual / wake, 3002 wake."""
    return build_frame(21020, {"ctrlCode": ctrl_code}, include_dinfo=False)


def request_status() -> str:
    return build_frame(20001)


def request_map() -> str:
    return build_frame(20002, include_dinfo=False)


def get_area_tactics() -> str:
    return build_frame(21004)


# --------------------------------------------------------------------------
# Zones / rooms
# --------------------------------------------------------------------------
def snap_to_lattice_mm(value: int) -> int:
    """Snap onto the firmware's 50 mm cell-centre lattice."""
    base = int(value / 50) * 50  # Kotlin int division truncates toward zero
    return base + 25 if value >= 0 else base - 25


def truncate_utf8(text: str, max_bytes: int) -> str:
    """Firmware strcpy()s names into 32-byte buffers; stay under 31 bytes."""
    data = text.encode("utf-8")
    if len(data) <= max_bytes:
        return text
    return data[:max_bytes].decode("utf-8", errors="ignore")


def rectangle_region(
    region_id: int,
    name: str,
    p1: tuple[int, int],
    p2: tuple[int, int],
    suction: str = "auto",
) -> dict[str, Any]:
    """An axis-aligned clean-zone polygon in the robot's wire format."""
    min_x, max_x = min(p1[0], p2[0]), max(p1[0], p2[0])
    min_y, max_y = min(p1[1], p2[1]), max(p1[1], p2[1])
    max_x = max(max_x, min_x + 100)
    max_y = max(max_y, min_y + 100)
    corners = [(min_x, min_y), (max_x, min_y), (max_x, max_y), (min_x, max_y)]
    return {
        "vertexs": [[snap_to_lattice_mm(x), snap_to_lattice_mm(y)] for x, y in corners],
        "active": "normal",
        "forbidType": "all",
        "cleanType": "sweepOnly",
        "workNoisy": suction,
        "name": truncate_utf8(name or f"Zone_{region_id}", 31),
        "tag": truncate_utf8(name.lower()[:16] or f"zone_{region_id}", 31),
        "id": region_id,
        "mode": "area",
    }


def zone_or_room_clean_sequence(
    stored_zone_ids: list[int] | None = None,
    extra_areas: list[dict[str, Any]] | None = None,
    segment_room_labels: list[int] | None = None,
) -> list[str]:
    """21023 ActiveRegions followed by 21005 appointClean (the mandatory 2-step sequence).

    -3 in cleanId keeps stored no-go zones active during the job.
    """
    data: dict[str, Any] = {"cleanId": [-3, *(stored_zone_ids or [])]}
    if extra_areas:
        data["extraAreas"] = extra_areas
    if segment_room_labels:
        data["segmentId"] = list(segment_room_labels)
    return [build_frame(21023, data), start_clean("appointClean")]


# --------------------------------------------------------------------------
# LZ4 + map
# --------------------------------------------------------------------------
def lz4_decompress_block(src: bytes, max_output: int) -> bytes:
    """Raw LZ4 block decompression (LZ4_decompress_safe)."""
    out = bytearray()
    i, n = 0, len(src)
    while i < n:
        token = src[i]
        i += 1
        lit = token >> 4
        if lit == 15:
            while i < n:
                extra = src[i]
                i += 1
                lit += extra
                if extra != 255:
                    break
        if i + lit > n:
            raise ValueError("LZ4 literal overruns input")
        if len(out) + lit > max_output:
            raise ValueError("LZ4 literal overruns output")
        out += src[i : i + lit]
        i += lit
        if i >= n:
            break
        if i + 2 > n:
            raise ValueError("LZ4 truncated offset")
        offset = src[i] | (src[i + 1] << 8)
        i += 2
        if offset == 0 or offset > len(out):
            raise ValueError(f"LZ4 bad offset {offset}")
        match = (token & 0x0F) + 4
        if match == 19:
            while i < n:
                extra = src[i]
                i += 1
                match += extra
                if extra != 255:
                    break
        if len(out) + match > max_output:
            raise ValueError("LZ4 match overruns output")
        start = len(out) - offset
        for k in range(match):  # byte-wise: matches may overlap (RLE)
            out.append(out[start + k])
    return bytes(out)


def decode_map_grid(b64: str, width: int, height: int) -> bytes:
    """Base64 + LZ4 occupancy grid -> width*height bytes (row-major)."""
    cleaned = b64.strip().replace(" ", "+").replace("\n", "").replace("\r", "")
    grid = lz4_decompress_block(base64.b64decode(cleaned), width * height)
    if len(grid) < width * height:  # pad rather than crash on a short grid
        grid += bytes([0x7F]) * (width * height - len(grid))
    return grid


def parse_map_20002(data: dict[str, Any]) -> dict[str, Any]:
    """Parse a 20002 floor-map upload."""
    width, height = int(data["width"]), int(data["height"])
    cells = decode_map_grid(data["map"], width, height)
    areas = []
    for idx, area in enumerate(data.get("area") or [], start=1):
        if isinstance(area, dict):
            areas.append({**area, "id": area.get("id", idx)})
    charge = data.get("chargeHandlePos")
    return {
        "sn": data.get("SN", ""),
        "map_id": data.get("mapId", 0),
        "auto_area_id": int(data.get("autoAreaId", 0)),
        "width": width,
        "height": height,
        "resolution": float(data.get("resolution", 0.05)),
        "x_min": float(data["x_min"]),
        "y_min": float(data["y_min"]),
        "cells": cells,
        "areas": areas,
        "charge_pos": (int(charge[0]), int(charge[1]))
        if isinstance(charge, list) and len(charge) >= 2
        else None,
        "charge_state": data.get("chargeHandleState", "notFind"),
    }


def room_labels(map_data: dict[str, Any]) -> list[int]:
    """Distinct auto-segmented room labels present in the grid."""
    return sorted(set(map_data["cells"]) - {0x00, 0x7F, 0xFF})


def world_mm_to_cell(map_data: dict[str, Any], x_mm: float, y_mm: float) -> tuple[float, float]:
    res = map_data["resolution"]
    col = (x_mm / 1000 - map_data["x_min"] - 0.05) / res
    row = (y_mm / 1000 - map_data["y_min"] - 0.05) / res
    return col, row


def cell_to_world_mm(map_data: dict[str, Any], col: float, row: float) -> tuple[int, int]:
    res = map_data["resolution"]
    return (
        round(1000 * (map_data["x_min"] + 0.05 + col * res)),
        round(1000 * (map_data["y_min"] + 0.05 + row * res)),
    )


def room_bounding_box_region(
    map_data: dict[str, Any], label: int, suction: str = "auto"
) -> dict[str, Any] | None:
    """Bounding-box clean zone for a room (firmware honours extraAreas even if segmentId is ignored)."""
    width, height, cells = map_data["width"], map_data["height"], map_data["cells"]
    needle = bytes([label])
    min_col = min_row = None
    max_col = max_row = -1
    for row in range(height):
        seg = cells[row * width : (row + 1) * width]
        first = seg.find(needle)
        if first < 0:
            continue
        last = seg.rfind(needle)
        min_row = row if min_row is None else min_row
        max_row = row
        min_col = first if min_col is None else min(min_col, first)
        max_col = max(max_col, last)
    if min_col is None or min_row is None:
        return None
    p1 = cell_to_world_mm(map_data, min_col, min_row)
    p2 = cell_to_world_mm(map_data, max_col, max_row)
    return rectangle_region(100 + label, f"Room_{label}", p1, p2, suction)


# --------------------------------------------------------------------------
# Telemetry
# --------------------------------------------------------------------------
def parse_status_20001(data: dict[str, Any], prev: dict[str, Any]) -> dict[str, Any]:
    """Merge a 20001 status upload into the previous known status."""
    st = dict(prev)
    if "mode" in data:
        st["mode"] = data["mode"]
    if "subMode" in data:
        st["sub_mode"] = data["subMode"]
    for key in ("elecReal", "elec"):
        if key in data:
            st["battery"] = max(0, min(100, int(data[key])))
            break
    if "cleanArea" in data:
        st["clean_area"] = round(float(data["cleanArea"]), 2)
    if "cleanTime" in data:
        st["clean_time"] = int(data["cleanTime"])
    if "workNoisy" in data and data["workNoisy"] in SUCTION_LEVELS:
        st["suction"] = data["workNoisy"]
    if "vol" in data:
        st["volume"] = max(0, min(10, int(data["vol"])))
    if "led" in data:
        st["led"] = int(data["led"]) == 1
    if "ldAvoidColli" in data:
        st["lidar_avoid"] = int(data["ldAvoidColli"]) == 1
    pos = data.get("pos")
    if isinstance(pos, list) and len(pos) >= 2:
        st["pos"] = (int(pos[0]), int(pos[1]))
    if "phi" in data:
        st["phi"] = int(data["phi"])
    if "errorState" in data and isinstance(data["errorState"], list):
        st["errors"] = [int(e) for e in data["errorState"] if int(e) != 0]
    st["updated"] = time.time()
    return st


def parse_path_21011(data: dict[str, Any]) -> list[tuple[int, int]]:
    """Cleaning path points (the low 2 bits of each coordinate are a type tag)."""
    arr = data.get("posArray") or []
    pts: list[tuple[int, int]] = []
    if arr and isinstance(arr[0], list):
        for pair in arr:
            if isinstance(pair, list) and len(pair) >= 2:
                pts.append((int(pair[0]) & ~3, int(pair[1]) & ~3))
    else:
        for i in range(0, len(arr) - 1, 2):
            pts.append((int(arr[i]) & ~3, int(arr[i + 1]) & ~3))
    return pts


def parse_form_fields(body: str) -> dict[str, str]:
    """Channel-A uploads are `k=v&...&data=<json>`; the JSON may hold raw '&'."""
    from urllib.parse import unquote_plus

    fields: dict[str, str] = {}
    marker = body.find("data=")
    head, raw = (body[:marker], body[marker + 5 :]) if marker >= 0 else (body, "")
    for pair in head.split("&"):
        if pair:
            k, _, v = pair.partition("=")
            fields[k] = unquote_plus(v)
    if marker >= 0:
        fields["data"] = unquote_plus(raw) if raw[:3].lower() == "%7b" else raw
    return fields


def api_state(mode: str | None) -> str:
    """Map the robot's raw mode to cleaning / returning / paused / docked / idle."""
    if mode in ("sweep", "smartClean", "appointClean", "depthTotalClean"):
        return "cleaning"
    if mode == "backcharge":
        return "returning"
    if mode in ("pause", "findchargerpause"):
        return "paused"
    if mode in ("charge", "fullcharge"):
        return "docked"
    return "idle"
