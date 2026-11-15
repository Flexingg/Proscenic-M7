"""Render the floor map to a PNG with Pillow (bundled with Home Assistant)."""

from __future__ import annotations

import io
import math
from typing import Any

from PIL import Image, ImageDraw, ImageFont

from . import protocol as p

BG = (15, 23, 42)
WALL = (203, 213, 225)
FREE = (51, 65, 85)
ROOM_COLORS = [
    (59, 130, 246), (34, 197, 94), (234, 179, 8), (168, 85, 247),
    (236, 72, 153), (20, 184, 166), (249, 115, 22), (99, 102, 241),
]
ZONE_COLORS = {"forbid": (239, 68, 68), "depth": (6, 182, 212), "normal": (34, 197, 94)}
MAX_DIM = 1000


def _room_color(label: int) -> tuple[int, int, int]:
    base = ROOM_COLORS[label % len(ROOM_COLORS)]
    return tuple(int(c * 0.55 + 20) for c in base)  # muted so walls/path stand out


def render_map_png(
    map_data: dict[str, Any],
    zones: list[dict[str, Any]],
    path: list[tuple[int, int]],
    robot_pos: tuple[int, int] | None,
    robot_phi_mrad: int,
    room_names: dict[int, str],
) -> bytes:
    """Draw grid, rooms, zones, path, dock and robot. Blocking: run in an executor."""
    width, height, cells = map_data["width"], map_data["height"], map_data["cells"]
    scale = max(2, min(MAX_DIM // max(width, 1), MAX_DIM // max(height, 1), 12))
    margin = 20

    # Build the grid image at 1px/cell (flipped: row 0 is the bottom), then scale up.
    img = Image.new("RGB", (width, height), BG)
    px = img.load()
    sums: dict[int, list[float]] = {}  # room label -> [sum col, sum row, count]
    for row in range(height):
        y = height - 1 - row
        base = row * width
        for col in range(width):
            v = cells[base + col]
            if v == 0x7F:
                continue
            if v == 0x00:
                px[col, y] = WALL
            elif v == 0xFF:
                px[col, y] = FREE
            else:
                px[col, y] = _room_color(v)
                acc = sums.setdefault(v, [0.0, 0.0, 0])
                acc[0] += col
                acc[1] += row
                acc[2] += 1
    img = img.resize((width * scale, height * scale), Image.NEAREST)
    canvas = Image.new("RGB", (img.width + 2 * margin, img.height + 2 * margin), BG)
    canvas.paste(img, (margin, margin))
    draw = ImageDraw.Draw(canvas)

    def to_px(x_mm: float, y_mm: float) -> tuple[float, float]:
        col, row = p.world_mm_to_cell(map_data, x_mm, y_mm)
        return margin + (col + 0.5) * scale, margin + (height - 0.5 - row) * scale

    try:
        font = ImageFont.load_default(size=16)
    except TypeError:  # very old Pillow: fixed-size bitmap font
        font = ImageFont.load_default()

    # Room names at their centroid
    for label, (sx, sy, n) in sums.items():
        cx = margin + (sx / n + 0.5) * scale
        cy = margin + (height - 0.5 - sy / n) * scale
        draw.text((cx, cy), room_names.get(label, f"Room {label}"), fill=(255, 255, 255), font=font, anchor="mm")

    # Zones
    for zone in zones:
        verts = zone.get("vertexs") or []
        pts = [to_px(v[0], v[1]) for v in verts if isinstance(v, list) and len(v) >= 2]
        if len(pts) >= 3:
            color = ZONE_COLORS.get(zone.get("active", "normal"), ZONE_COLORS["normal"])
            draw.line([*pts, pts[0]], fill=color, width=2)

    # Cleaning path
    if len(path) >= 2:
        draw.line([to_px(x, y) for x, y in path], fill=(56, 189, 248), width=max(1, scale // 3))

    # Dock
    if map_data.get("charge_pos"):
        x, y = to_px(*map_data["charge_pos"])
        draw.rectangle([x - 6, y - 6, x + 6, y + 6], outline=(34, 197, 94), width=3)

    # Robot
    if robot_pos and robot_pos != (0, 0):
        x, y = to_px(*robot_pos)
        draw.ellipse([x - 8, y - 8, x + 8, y + 8], fill=(250, 250, 250), outline=(59, 130, 246), width=3)
        heading = robot_phi_mrad / 1000
        draw.line([x, y, x + 14 * math.cos(heading), y - 14 * math.sin(heading)], fill=(59, 130, 246), width=3)

    out = io.BytesIO()
    canvas.save(out, format="PNG")
    return out.getvalue()
