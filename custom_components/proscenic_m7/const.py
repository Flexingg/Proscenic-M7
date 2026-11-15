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
