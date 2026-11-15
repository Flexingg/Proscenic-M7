# Home Assistant integration

`custom_components/proscenic_m7` gives Home Assistant full local control of the
vacuum. The robot has no local API of its own: it phones home to a "cloud" server, and
this project provides that server. It can run either on your phone (the Android app) or
inside Home Assistant itself.

```
Phone mode:   Robot ──TCP :8081──▶ Android app ◀──HTTP :8080/api/*── Home Assistant
Direct mode:  Robot ──TCP :8081──▶ Home Assistant   (no phone involved)
```

There are two ways to connect, picked when you add the integration:

* **Directly (recommended once it works for you):** Home Assistant itself acts as the
  robot's cloud server. No phone, no app running. See [Direct mode](#direct-mode-no-phone).
* **Through the phone app:** the phone must stay on, on your Wi-Fi, with the app running.
  Give it a fixed IP (DHCP reservation) in your router.

## Install

**HACS (custom repository):** HACS → ⋮ → Custom repositories → add
`https://github.com/Flexingg/Proscenic-M7` as type *Integration* → install →
restart Home Assistant.

**Manual:** copy `custom_components/proscenic_m7` into your HA config directory's
`custom_components/` folder and restart.

Then: Settings → Devices & services → Add integration → *Proscenic M7* and choose a
connection. For the phone: enter the phone's IP (port `8080`). Needs Home Assistant
2025.1 or newer. You need the
app build that includes the extended `/api` (release 0.0.6 or newer): older builds
lack pause/resume, volume, LED, room names and the map image.

If the phone's IP changes: open the integration → ⋮ → Reconfigure.

(The phone-app note above applies to *phone mode* only; direct mode does not need the app.)

## Direct mode (no phone)

In direct mode the integration runs the same two servers the phone app runs (HTTP
`:8080`, robot TCP `:8081`) inside Home Assistant, and tells the robot to connect to
them. The robot has only one "cloud", so **while it points at Home Assistant the phone
app cannot control it, and the reverse.** Switching back is one tap in the app
(Pairing & HA → Scan & Re-Point Robot).

**Requirements**

* The robot is already on your home Wi-Fi (paired once, e.g. with the app).
* Home Assistant can reach the robot on the LAN and can listen on two TCP ports.
  Home Assistant OS and Container with `network_mode: host` work. A Docker *bridge*
  network does not (the robot could not find or reach it).
* Ports `8080` and `8081` are free on the Home Assistant host (both configurable).

**Set up**

1. Add integration → *Proscenic M7* → **Directly**.
2. Check the Home Assistant IP shown (the robot will connect to it; give it a fixed
   address), leave *Robot IP* empty to scan your network, and keep
   *Point my robot at Home Assistant now* ticked.
3. Within a minute the *Robot connected* sensor turns on and entities come alive.
   If re-pointing failed you get a notification with the reason; fix it and press
   **Point robot at Home Assistant** on the device page.

**Notes**

* Re-pointing uses the robot's LAN control port (UDP 7913), the same steps as the app's
  re-point: `setUrl` (HTTP) → `setUrl` (gateway) → `setID`. It never sends `bindOk`.
* Commands are **never queued** while the robot is offline: they fail with an error
  rather than running later, when you no longer expect them.
* The robot only numbers its rooms. Name them under *Configure* on the integration,
  e.g. `1=Kitchen, 2=Hallway` (numbers are in the vacuum's `rooms` attribute), then
  use the names in `proscenic_m7.clean_rooms`.
* Home Assistant renders the map itself (rooms, zones, no-go areas, path, dock, robot).
* If Home Assistant is off, the robot has no cloud and goes offline until it is back.
* Not yet verified on real hardware. The protocol code is a port of the Android app's
  and is covered by tests against a simulated robot; if something misbehaves, enable
  debug logging for `custom_components.proscenic_m7` and open an issue.

## What you get

| Entity | Purpose |
|---|---|
| `vacuum.proscenic_m7` | Start (resumes if paused), pause, stop, return to dock, locate, spot clean, suction (`quiet`/`auto`/`strong`/`max`), raw `send_command` |
| `sensor` battery, cleaned area, cleaning time | Telemetry |
| `sensor` mode, error code | Diagnostics |
| `binary_sensor` robot connected, charging, problem | Robot connected stays available when the robot drops off; all other robot entities go unavailable |
| `button` deep clean (2×), empty dust bin, wake | One-shot actions. *Wake* works even when the robot looks asleep |
| `button` point robot at Home Assistant | Direct mode only: (re-)point the robot's cloud at this Home Assistant |
| `camera` map | The floor map as an image: rooms, zones, cleaning path, dock and robot |
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

## Map

`camera.proscenic_m7_map` shows the floor map the app renders (rooms with names,
zones, the cleaning path, dock and robot position). Add it to a dashboard:

```yaml
type: picture-entity
entity: camera.proscenic_m7_map
show_state: false
show_name: false
camera_view: auto
```

The phone renders the PNG on demand and caches it for 3 seconds, so refresh
sparingly. It shows the last known map even while the robot is asleep, and is
unavailable only until the first map has been uploaded.

## Automations instead of the app's schedules

Home Assistant can do everything the app's schedules do, plus conditions the app
cannot (presence, weather, calendars). The app's own schedules still work; use one
system or the other, not both, or the robot will run twice.

**Blueprint (easiest).** Import it with one click:
[![Import blueprint](https://my.home-assistant.io/badges/blueprint_import.svg)](https://my.home-assistant.io/redirect/blueprint_import/?blueprint_url=https%3A%2F%2Fgithub.com%2FFlexingg%2FProscenic-M7%2Fblob%2Fmain%2Fblueprints%2Fautomation%2Fproscenic_m7%2Fscheduled_clean.yaml)
Then Settings → Automations → Create → *Proscenic M7 - Scheduled clean*. Pick the
vacuum, time, weekdays, rooms (empty = whole home), suction, and optionally a person
or group that makes it skip when someone is home. Create one automation per schedule.

**Plain YAML examples:**

```yaml
# Weekdays 09:00, kitchen and hallway, only if nobody is home
- alias: Vacuum weekday mornings
  triggers: [{ trigger: time, at: "09:00:00" }]
  conditions:
    - { condition: time, weekday: [mon, tue, wed, thu, fri] }
    - { condition: not, conditions: [{ condition: state, entity_id: group.family, state: home }] }
  actions:
    - action: proscenic_m7.clean_rooms
      target: { entity_id: vacuum.proscenic_m7 }
      data: { rooms: ["Kitchen", "Hallway"] }

# Clean when the last person leaves, but only in daytime (replaces "quiet hours")
- alias: Vacuum when house empties
  triggers: [{ trigger: state, entity_id: group.family, to: not_home, for: "00:10:00" }]
  conditions: [{ condition: time, after: "08:00:00", before: "21:00:00" }]
  actions: [{ action: vacuum.start, target: { entity_id: vacuum.proscenic_m7 } }]

# Send it home and tell me if it gets stuck
- alias: Vacuum problem
  triggers: [{ trigger: state, entity_id: binary_sensor.proscenic_m7_problem, to: "on" }]
  actions:
    - action: notify.notify
      data: { message: "Vacuum error {{ states('sensor.proscenic_m7_error_code') }}" }
```

**Quiet hours:** just do not schedule runs in those hours. The `time` condition above
is the equivalent of the app's overnight do-not-disturb window.

## Not covered

- **Editing the app's own schedules from Home Assistant.** Use automations as above.
- **Security.** The bridge has no authentication, same as before. Keep it on your
  LAN and never expose port 8080 to the internet.

## Raw API

`GET /api/status`, `GET /api/map.json`, `GET /api/map.png`, `POST /api/command {"action": "...", ...}`.
Actions: `start`, `deep_clean`, `pause`, `resume`, `play_pause`, `stop`,
`return_to_base`, `locate`, `empty_bin`, `spot_clean`, `wake`,
`set_fan_speed {fan_speed}`, `set_volume {value 0-10}`, `set_led {value}`,
`set_lidar_avoid {value}`, `clean_rooms {rooms: [ids]}`, `clean_zones {zones: [ids]}`.

## Development

```
pip install pytest-homeassistant-custom-component PyTurboJPEG
pytest tests/ha -c tests/ha/pytest.ini
```
