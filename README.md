# Proscenic M7 / M7 Pro — 100% Local Android Controller & Home Assistant Bridge

A zero-cloud, local-first Android application and embedded replacement server for the **Proscenic M7 / M7 Pro** (`LS_S6` / LDRobot platform) robot vacuum, designed in **OPPO's ColorOS 17 Living Flow Design (流体设计)** language.

---

## Key Features

1. **100% Local Replacement Server (`:8080` HTTP + `:8081` TCP Push Gateway)**
   - Runs directly on your Android phone (`LocalVacuumServer.kt`) or connects to an external LAN host.
   - Replies to Channel-B `21006` heartbeats with `isExistConnect: true` so the vacuum continuously uploads real-time `20002` floor maps and `21011` cleaning paths.
   - Sends commands with `"encrypt": 0` (bypassing `AES-128-ECB` in `network_proxy`) framed with `#\t#`.

2. **No-Root SoftAP UDP `7913` Re-Homing Wizard (`RehomeClient.kt`)**
   - Redirects the robot from Proscenic's cloud (`47.91.67.181`) to your local IP over its `192.168.78.1:7913` SoftAP pairing socket (`setUrl` + `setSta` + `applyCfg`, omitting the buggy `bindOk` command).

3. **Interactive 2D LZ4 Occupancy Grid & Path Renderer (`MapCanvasView.kt`, `Lz4Codec.kt`)**
   - Decodes Base64 + raw LZ4 `width × height` 50 mm grids (`0x00` walls, `0x7F` unknown, `0xFF` free floor, `0x01..0xFE` watershed-segmented rooms).
   - Displays room names at centroids, live robot pose (`posX`, `posY`, `phi` mrad), charging dock, and `21011` cleaning trails.

4. **Zones, No-Go / No-Mop Polygons, Room Selection & Placed Spot Clean**
   - Draw rectangular `CLEAN_NORMAL`, `CLEAN_DEEP (2x)`, `NO_GO`, and `NO_MOP` zones (`21003 SetAreaTactics`, snapped to the 50 mm grid and truncated to $\le 31$ UTF-8 bytes to prevent the `task_manager` stack buffer overflow).
   - Dispatches room (`segmentId`) and zone (`cleanId`) cleans using the mandatory 2-step sequence: `21023 ActiveRegions` followed by `21005 {"mode":"appointClean"}`.
   - Split, merge, or reset auto-segmented rooms (`21030`).

5. **Schedules & Overnight Quiet Hours (`21001` / `21002`)**
   - Manage weekly schedules (`repeat` 7-char Sun–Sat bitmask, suction level, $1\times / 2\times$ passes, Whole-Home vs. Saved Zones).
   - Automatic midnight split for overnight Do-Not-Disturb windows (`active: false`).

6. **Home Assistant Local REST API Bridge**
   - Exposes `GET /api/status`, `GET /api/map.json`, and `POST /api/command` on `:8080` with ready-to-copy `configuration.yaml` snippets.

7. **ColorOS 17 "Living Flow Design" (流体设计) UI**
   - **Luminous Effect (凝光视效):** Three-tier frosted glass scale (`glass.thin`, `glass.regular`, `glass.thick`), 1dp vertical gradient **Contour Glow**, progressive edge scrims, and **Luminous Comet Seek Bar** for mop water control.
   - **Fluid Motion (流体动效):** Floating pill island navigation (`FloatingIslandNavBar`) with horizontal swipe-to-switch and a morphing inner indicator pill (`spring.morph`).
   - **Flexible Feedback (柔性反馈):** Physical press compression (`spring.press`), elastic rebound (`spring.release`), radial pointer touch glow, and semantic haptics, plus full accessibility guardrails (**Reduce Transparency**, **Reduce Motion**, **High-Contrast Contour**, and **Light/Dark OLED modes**).

---

## Repository Structure

- [`docs/PROTOCOL_OVERVIEW.md`](docs/PROTOCOL_OVERVIEW.md) — Architectural guide to the Proscenic M7 3-channel protocol.
- [`docs/reverse-engineering/`](docs/reverse-engineering/) — Complete set of 27 reverse-engineering specs (`PROTOCOL.md`, `MAP.md`, `FUNC_MAP.md`, `FUNC_COMMANDS.md`, `FUNC_STATUS.md`, `ZONE_CLEAN.md`, `CHANNEL_B_INBOUND.md`, `rehome.py`, `fakerobot.py`, `map2png.py`, etc.).
- [`app/src/main/java/com/proscenic/m7/protocol/`](app/src/main/java/com/proscenic/m7/protocol/) — Pure-Kotlin LZ4 codec, wire frame builder/parser, domain models, and UDP `7913` re-homing client.
- [`app/src/main/java/com/proscenic/m7/server/LocalVacuumServer.kt`](app/src/main/java/com/proscenic/m7/server/LocalVacuumServer.kt) — Embedded HTTP `:8080` & TCP `:8081` server + Home Assistant bridge.
- [`app/src/main/java/com/proscenic/m7/ui/theme/LivingFlowTheme.kt`](app/src/main/java/com/proscenic/m7/ui/theme/LivingFlowTheme.kt) — ColorOS 17 Living Flow Design token file, modifiers, and accessibility fallbacks.
- [`app/src/main/java/com/proscenic/m7/ui/components/LivingFlowComponents.kt`](app/src/main/java/com/proscenic/m7/ui/components/LivingFlowComponents.kt) — Floating Island Nav Bar, Morphing Segmented Control, Luminous Comet Slider, Elastic Switch, and Morphing Status Capsule.
