#!/usr/bin/env python3
"""Small allow-listed Home Assistant controller for On-mom."""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any, Callable
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

CONFIG_KEYS = {"schema_version", "home_assistant_url", "token_file", "devices"}
DEVICE_KEYS = {"id", "name", "entity_id", "kind", "actions"}
KINDS = {"LIGHT", "SWITCH", "FAN", "LOCK", "COVER"}
ACTION_SERVICE = {
    "TURN_ON": "turn_on",
    "TURN_OFF": "turn_off",
    "TOGGLE": "toggle",
    "LOCK": "lock",
    "UNLOCK": "unlock",
    "OPEN": "open_cover",
    "CLOSE": "close_cover",
}
KIND_ACTIONS = {
    "LIGHT": {"TURN_ON", "TURN_OFF", "TOGGLE"},
    "SWITCH": {"TURN_ON", "TURN_OFF", "TOGGLE"},
    "FAN": {"TURN_ON", "TURN_OFF", "TOGGLE"},
    "LOCK": {"LOCK", "UNLOCK"},
    "COVER": {"OPEN", "CLOSE"},
}


class IotControlError(ValueError):
    pass


class HomeAssistantIotController:
    def __init__(
        self,
        config_path: Path,
        requester: Callable[[str, str, str, dict[str, Any] | None], Any] | None = None,
    ):
        self.config_path = config_path.expanduser().resolve()
        self._requester = requester or self._request
        self._config = self._load_config()

    @property
    def configured(self) -> bool:
        return self._config is not None and self._token().strip() != ""

    def status(self) -> dict[str, Any]:
        return {
            "configured": self.configured,
            "device_count": len(self._config["devices"]) if self._config else 0,
            "config_file": self.config_path.name,
        }

    def list_devices(self) -> dict[str, Any]:
        if not self._config:
            return {"schema": "iot-device-list-v1", **self.status(), "devices": []}
        token = self._token()
        devices = []
        for device in self._config["devices"]:
            state = "UNKNOWN"
            available = False
            if token:
                try:
                    response = self._requester(
                        "GET",
                        self._url(f"/api/states/{device['entity_id']}"),
                        token,
                        None,
                    )
                    state = str(response.get("state", "UNKNOWN")).upper()
                    available = state not in {"UNAVAILABLE", "UNKNOWN"}
                except (OSError, ValueError, KeyError):
                    pass
            devices.append(
                {
                    "id": device["id"],
                    "name": device["name"],
                    "kind": device["kind"],
                    "state": state,
                    "available": available,
                    "actions": device["actions"],
                }
            )
        return {"schema": "iot-device-list-v1", **self.status(), "devices": devices}

    def execute(self, device_id: str, action: str) -> dict[str, Any]:
        if not self._config:
            raise IotControlError("IoT device config is missing")
        token = self._token()
        if not token:
            raise IotControlError("Home Assistant token is missing")
        device = next(
            (item for item in self._config["devices"] if item["id"] == device_id),
            None,
        )
        normalized_action = str(action).upper()
        if device is None:
            raise IotControlError("unknown device")
        if normalized_action not in device["actions"]:
            raise IotControlError("action is not allowed for this device")
        domain = device["entity_id"].split(".", 1)[0]
        service = ACTION_SERVICE[normalized_action]
        self._requester(
            "POST",
            self._url(f"/api/services/{domain}/{service}"),
            token,
            {"entity_id": device["entity_id"]},
        )
        return {
            "accepted": True,
            "device_id": device["id"],
            "action": normalized_action,
        }

    def _load_config(self) -> dict[str, Any] | None:
        if not self.config_path.exists():
            return None
        try:
            payload = json.loads(self.config_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise IotControlError(f"could not read IoT config: {exc}") from exc
        if not isinstance(payload, dict) or set(payload) != CONFIG_KEYS:
            raise IotControlError("IoT config fields do not match schema")
        if payload.get("schema_version") != 1:
            raise IotControlError("unsupported IoT config schema_version")
        url = payload.get("home_assistant_url")
        if not isinstance(url, str) or not url.startswith(("http://", "https://")):
            raise IotControlError("home_assistant_url must use http or https")
        if not isinstance(payload.get("token_file"), str) or not payload["token_file"]:
            raise IotControlError("token_file is required")
        devices = payload.get("devices")
        if not isinstance(devices, list) or len(devices) > 64:
            raise IotControlError("devices must be a list with at most 64 items")
        ids = set()
        for device in devices:
            if not isinstance(device, dict) or set(device) != DEVICE_KEYS:
                raise IotControlError("device fields do not match schema")
            device_id = device.get("id")
            entity_id = device.get("entity_id")
            kind = device.get("kind")
            actions = device.get("actions")
            if (
                not isinstance(device_id, str)
                or not device_id
                or device_id in ids
                or len(device_id) > 64
            ):
                raise IotControlError("device id is invalid or duplicated")
            if (
                not isinstance(entity_id, str)
                or "." not in entity_id
                or entity_id.split(".", 1)[0] not in {"light", "switch", "fan", "lock", "cover"}
            ):
                raise IotControlError("unsupported Home Assistant entity")
            if kind not in KINDS or entity_id.split(".", 1)[0].upper() != kind:
                raise IotControlError("device kind does not match entity domain")
            if (
                not isinstance(actions, list)
                or not actions
                or len(actions) != len(set(actions))
                or any(action not in KIND_ACTIONS[kind] for action in actions)
            ):
                raise IotControlError("device actions are invalid")
            if not isinstance(device.get("name"), str) or not device["name"].strip():
                raise IotControlError("device name is required")
            ids.add(device_id)
        return payload

    def _token(self) -> str:
        if not self._config:
            return ""
        token_path = Path(os.path.expandvars(self._config["token_file"])).expanduser()
        if not token_path.is_absolute():
            token_path = self.config_path.parent / token_path
        if not token_path.exists():
            return ""
        if os.name == "posix" and token_path.stat().st_mode & 0o077:
            raise IotControlError("Home Assistant token file permissions must be 0600")
        return token_path.read_text(encoding="utf-8").strip()

    def _url(self, path: str) -> str:
        return self._config["home_assistant_url"].rstrip("/") + path

    @staticmethod
    def _request(
        method: str,
        url: str,
        token: str,
        body: dict[str, Any] | None,
    ) -> Any:
        encoded = None if body is None else json.dumps(body).encode("utf-8")
        request = Request(
            url,
            method=method,
            data=encoded,
            headers={
                "Authorization": f"Bearer {token}",
                "Accept": "application/json",
                "Content-Type": "application/json",
            },
        )
        try:
            with urlopen(request, timeout=5) as response:
                raw = response.read(256 * 1024)
                return json.loads(raw.decode("utf-8")) if raw else {}
        except (HTTPError, URLError, TimeoutError, json.JSONDecodeError) as exc:
            raise IotControlError("Home Assistant request failed") from exc
