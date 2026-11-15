"""Config flow: point Home Assistant at the phone running the Proscenic app."""

from __future__ import annotations

from typing import Any

import voluptuous as vol

from homeassistant.config_entries import ConfigFlow, ConfigFlowResult
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .api import ProscenicApi, ProscenicApiError
from .const import CONF_HOST, CONF_PORT, DEFAULT_PORT, DOMAIN


class ProscenicConfigFlow(ConfigFlow, domain=DOMAIN):
    """Handle a config flow."""

    VERSION = 1

    async def _validate(self, host: str, port: int) -> dict[str, Any]:
        api = ProscenicApi(async_get_clientsession(self.hass), host, port)
        return await api.get_status()

    @staticmethod
    def _schema(host: str = "", port: int = DEFAULT_PORT) -> vol.Schema:
        return vol.Schema(
            {
                vol.Required(CONF_HOST, default=host): str,
                vol.Required(CONF_PORT, default=port): vol.All(
                    vol.Coerce(int), vol.Range(min=1, max=65535)
                ),
            }
        )

    async def async_step_user(
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
                # Prefer the robot's serial number so the entry survives the
                # phone getting a new IP; fall back to host:port if the robot
                # has not connected to the app yet.
                unique = status.get("sn") or f"{host}:{port}"
                await self.async_set_unique_id(unique)
                self._abort_if_unique_id_configured(
                    updates={CONF_HOST: host, CONF_PORT: port}
                )
                return self.async_create_entry(
                    title="Proscenic M7",
                    data={CONF_HOST: host, CONF_PORT: port},
                )
        return self.async_show_form(
            step_id="user",
            data_schema=self._schema(
                (user_input or {}).get(CONF_HOST, ""),
                (user_input or {}).get(CONF_PORT, DEFAULT_PORT),
            ),
            errors=errors,
        )

    async def async_step_reconfigure(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        """Change the phone's address (e.g. after a DHCP change)."""
        entry = self._get_reconfigure_entry()
        errors: dict[str, str] = {}
        if user_input is not None:
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
        return self.async_show_form(
            step_id="reconfigure",
            data_schema=self._schema(
                entry.data[CONF_HOST], entry.data.get(CONF_PORT, DEFAULT_PORT)
            ),
            errors=errors,
        )
