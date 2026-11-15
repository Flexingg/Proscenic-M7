"""Constants for the Proscenic M7 integration."""

from datetime import timedelta

DOMAIN = "proscenic_m7"

CONF_HOST = "host"
CONF_PORT = "port"
DEFAULT_PORT = 8080

SCAN_INTERVAL = timedelta(seconds=5)

# Suction levels understood by the phone app's /api/command `set_fan_speed`.
FAN_SPEEDS = ["quiet", "auto", "strong", "max"]

SERVICE_CLEAN_ROOMS = "clean_rooms"
SERVICE_CLEAN_ZONES = "clean_zones"
ATTR_ROOMS = "rooms"
ATTR_ZONES = "zones"

# Connection modes
CONF_MODE = "mode"
MODE_PHONE = "phone"  # talk to the Android app's HTTP bridge
MODE_DIRECT = "direct"  # Home Assistant is the robot's cloud

# Direct mode
CONF_HTTP_PORT = "http_port"
CONF_GATEWAY_PORT = "gateway_port"
CONF_ADVERTISE_IP = "advertise_ip"
CONF_ROBOT_IP = "robot_ip"
CONF_REPOINT = "repoint"
CONF_ROOM_NAMES = "room_names"
DEFAULT_HTTP_PORT = 8080
DEFAULT_GATEWAY_PORT = 8081
