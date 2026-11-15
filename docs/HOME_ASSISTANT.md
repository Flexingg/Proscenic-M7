# Home Assistant integration

`custom_components/proscenic_m7` gives Home Assistant full local control of the
vacuum. It does not talk to the robot directly: the robot talks to the Android
app (which acts as its replacement cloud server), and the integration talks to
the app's HTTP bridge on port `8080`.

```
Robot ──TCP :8081──▶ Android app ◀──HTTP :8080/api/*── Home Assistant
```

So the phone must stay on, on your Wi-Fi, with the app running. Give it a fixed
IP (DHCP reservation) in your router.

## Install

**HACS (custom repository):** HACS → ⋮ → Custom repositories → add
`https://github.com/Flexingg/Proscenic-M7` as type *Integration* → install →
restart Home Assistant.

**Manual:** copy `custom_components/proscenic_m7` into your HA config directory's
`custom_components/` folder and restart.

Then: Settings → Devices & services → Add integration → *Proscenic M7* → enter
the phone's IP (port `8080`). Needs Home Assistant 2025.1 or newer. You need the
app build that includes the extended `/api` (release 0.0.6 or newer): older builds
lack pause/resume, volume, LED and room names.

If the phone's IP changes: open the integration → ⋮ → Reconfigure.

## What you get

| Entity | Purpose |
|---|---|
| `vacuum.proscenic_m7` | Start (resumes if paused), pause, stop, return to dock, locate, spot clean, suction (`quiet`/`auto`/`strong`/`max`), raw `send_command` |
| `sensor` battery, cleaned area, cleaning time | Telemetry |
| `sensor` mode, error code | Diagnostics |
| `binary_sensor` robot connected, charging, problem | Robot connected stays available when the robot drops off; all other robot entities go unavailable |
| `button` deep clean (2×), empty dust bin, wake | One-shot actions. *Wake* works even when the robot looks asleep |
| `select` suction | Same as vacuum fan speed |
| `number` volume (0–10) | Voice prompt volume |
| `switch` LED, lidar collision avoidance | Robot settings |

### Services

```yaml
# Rooms by name (as named in the app) or by id
service: proscenic_m7.clean_rooms
target: { entity_id: vacuum.proscenic_m7 }
data: { rooms: ["Kitchen", "Hallway"] }

# Saved cleaning zones by id (listed in the vacuum's `zones` attribute; no-go zones are rejected)
service: proscenic_m7.clean_zones
target: { entity_id: vacuum.proscenic_m7 }
data: { zones: [7] }
```

The vacuum entity's `rooms` and `zones` attributes list the current ids/names.
Room names come from the app, so rename rooms there.

## Not covered (yet)

- **Map image.** The app does not serve the map as a picture; view it in the app.
- **Schedules and do-not-disturb.** Managed in the app; use HA automations instead.
- **Security.** The bridge has no authentication, same as before. Keep it on your
  LAN and never expose port 8080 to the internet.

## Raw API

`GET /api/status`, `GET /api/map.json`, `POST /api/command {"action": "...", ...}`.
Actions: `start`, `deep_clean`, `pause`, `resume`, `play_pause`, `stop`,
`return_to_base`, `locate`, `empty_bin`, `spot_clean`, `wake`,
`set_fan_speed {fan_speed}`, `set_volume {value 0-10}`, `set_led {value}`,
`set_lidar_avoid {value}`, `clean_rooms {rooms: [ids]}`, `clean_zones {zones: [ids]}`.

## Development

```
pip install pytest-homeassistant-custom-component
pytest tests/ha -c tests/ha/pytest.ini
```
