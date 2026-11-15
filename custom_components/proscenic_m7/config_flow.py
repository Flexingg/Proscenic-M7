"""Config flow: connect through the phone app, or directly (Home Assistant is the robot's cloud)."""

from __future__ import annotations

import socket
from typing import Any

import voluptuous as vol

from homeassistant.components import network
from homeassistant.config_entries import (
    ConfigEntry,
    ConfigFlow,
    ConfigFlowResult,
    OptionsFlow,
)
from homeassistant.core import callback
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .api import ProscenicApi, ProscenicApiError
from .const import (
    CONF_ADVERTISE_IP,
    CONF_GATEWAY_PORT,
    CONF_HOST,
    CONF_HTTP_PORT,
    CONF_MODE,
    CONF_PORT,
    CONF_REPOINT,
    CONF_ROBOT_IP,
    CONF_ROOM_NAMES,
    DEFAULT_GATEWAY_PORT,
    DEFAULT_HTTP_PORT,
    DEFAULT_PORT,
    DOMAIN,
    MODE_DIRECT,
    MODE_PHONE,
)

PORT = vol.All(vol.Coerce(int), vol.Range(min=1, max=65535))


def _ports_free(*ports: int) -> bool:
    """True if every TCP port can be bound right now."""
    socks = []
    try:
        for port in ports:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            socks.append(sock)
            sock.bind(("0.0.0.0", port))
        return True
    except OSError:
        return False
    finally:
        for sock in socks:
            sock.close()


class ProscenicConfigFlow(ConfigFlow, domain=DOMAIN):
    """Handle a config flow."""

    VERSION = 1

    @staticmethod
    @callback
    def async_get_options_flow(config_entry: ConfigEntry) -> OptionsFlow:
        return ProscenicOptionsFlow()

    async def async_step_user(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        return self.async_show_menu(step_id="user", menu_options=["direct", "phone"])

    # ------------------------------------------------------------------ phone bridge
    async def _validate(self, host: str, port: int) -> dict[str, Any]:
        api = ProscenicApi(async_get_clientsession(self.hass), host, port)
        return await api.get_status()

    @staticmethod
    def _phone_schema(host: str = "", port: int = DEFAULT_PORT) -> vol.Schema:
        return vol.Schema(
            {vol.Required(CONF_HOST, default=host): str, vol.Required(CONF_PORT, default=port): PORT}
        )

    async def async_step_phone(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        if user_input is not None:
            host = user_input[CONF_HOST].strip()
            port = user_input[CONF_PORT]
            try:
                status = await self._validate(host, port)
            except ProscenicApiError:
                errors["base"] = "cannot_connect"
            else:
                # Prefer the robot's serial number so the entry survives the phone
                # getting a new IP; fall back to host:port if the robot has not
                # connected to the app yet.
                await self.async_set_unique_id(status.get("sn") or f"{host}:{port}")
                self._abort_if_unique_id_configured(updates={CONF_HOST: host, CONF_PORT: port})
                return self.async_create_entry(
                    title="Proscenic M7",
                    data={CONF_MODE: MODE_PHONE, CONF_HOST: host, CONF_PORT: port},
                )
        return self.async_show_form(
            step_id="phone",
            data_schema=self._phone_schema(
                (user_input or {}).get(CONF_HOST, ""), (user_input or {}).get(CONF_PORT, DEFAULT_PORT)
            ),
            errors=errors,
        )

    # ------------------------------------------------------------------ direct
    async def async_step_direct(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        if user_input is not None:
            http_port = user_input[CONF_HTTP_PORT]
            gateway_port = user_input[CONF_GATEWAY_PORT]
            if http_port == gateway_port:
                errors["base"] = "same_port"
            else:
                await self.async_set_unique_id(f"direct:{gateway_port}")
                self._abort_if_unique_id_configured()
                if not await self.hass.async_add_executor_job(_ports_free, http_port, gateway_port):
                    errors["base"] = "port_in_use"
            if not errors:
                return self.async_create_entry(
                    title="Proscenic M7",
                    data={
                        CONF_MODE: MODE_DIRECT,
                        CONF_ADVERTISE_IP: user_input[CONF_ADVERTISE_IP].strip(),
                        CONF_HTTP_PORT: http_port,
                        CONF_GATEWAY_PORT: gateway_port,
                        CONF_ROBOT_IP: user_input.get(CONF_ROBOT_IP, "").strip(),
                        CONF_REPOINT: user_input[CONF_REPOINT],
                    },
                )

        default_ip = (user_input or {}).get(CONF_ADVERTISE_IP) or await network.async_get_source_ip(self.hass)
        return self.async_show_form(
            step_id="direct",
            data_schema=vol.Schema(
                {
                    vol.Required(CONF_ADVERTISE_IP, default=default_ip): str,
                    vol.Required(CONF_HTTP_PORT, default=DEFAULT_HTTP_PORT): PORT,
                    vol.Required(CONF_GATEWAY_PORT, default=DEFAULT_GATEWAY_PORT): PORT,
                    vol.Optional(CONF_ROBOT_IP, default=(user_input or {}).get(CONF_ROBOT_IP, "")): str,
                    vol.Required(CONF_REPOINT, default=True): bool,
                }
            ),
            errors=errors,
        )

    # ------------------------------------------------------------------ reconfigure
    async def async_step_reconfigure(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        """Change the phone's address (phone mode) or the advertised IP (direct mode)."""
        entry = self._get_reconfigure_entry()
        errors: dict[str, str] = {}
        direct = entry.data.get(CONF_MODE) == MODE_DIRECT
        if user_input is not None:
            if direct:
                return self.async_update_reload_and_abort(
                    entry,
                    data_updates={
                        CONF_ADVERTISE_IP: user_input[CONF_ADVERTISE_IP].strip(),
                        CONF_ROBOT_IP: user_input.get(CONF_ROBOT_IP, "").strip(),
                        CONF_REPOINT: user_input[CONF_REPOINT],
                    },
                )
            host = user_input[CONF_HOST].strip()
            port = user_input[CONF_PORT]
            try:
                await self._validate(host, port)
            except ProscenicApiError:
                errors["base"] = "cannot_connect"
            else:
                return self.async_update_reload_and_abort(
                    entry, data_updates={CONF_HOST: host, CONF_PORT: port}
                )
        if direct:
            schema = vol.Schema(
                {
                    vol.Required(CONF_ADVERTISE_IP, default=entry.data.get(CONF_ADVERTISE_IP, "")): str,
                    vol.Optional(CONF_ROBOT_IP, default=entry.data.get(CONF_ROBOT_IP, "")): str,
                    vol.Required(CONF_REPOINT, default=True): bool,
                }
            )
            return self.async_show_form(step_id="reconfigure", data_schema=schema, errors=errors)
        return self.async_show_form(
            step_id="reconfigure",
            data_schema=self._phone_schema(entry.data[CONF_HOST], entry.data.get(CONF_PORT, DEFAULT_PORT)),
            errors=errors,
        )


class ProscenicOptionsFlow(OptionsFlow):
    """Direct mode: name your rooms (the robot only knows them as numbers)."""

    async def async_step_init(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        if self.config_entry.data.get(CONF_MODE) != MODE_DIRECT:
            return self.async_abort(reason="not_supported")
        if user_input is not None:
            return self.async_create_entry(data=user_input)
        return self.async_show_form(
            step_id="init",
            data_schema=vol.Schema(
                {
                    vol.Optional(
                        CONF_ROOM_NAMES,
                        default=self.config_entry.options.get(CONF_ROOM_NAMES, ""),
                    ): str
                }
            ),
        )
