#!/usr/bin/env python3
"""Read-only Nest check via the Google SDM API, independent of Home Assistant running.

Reads the OAuth client and refresh token from HA's .storage at runtime (nothing is copied or
printed), exchanges them for an access token, lists thermostats and prints mode, heat setpoint,
ambient temperature and HVAC status.

Usage: nest_check.py [HA_STORAGE_DIR]
"""
import json
import sys
import urllib.parse
import urllib.request
from pathlib import Path

STORAGE = Path(sys.argv[1] if len(sys.argv) > 1 else "~/projects/home-lab/homeassistant/.storage").expanduser()
P = "sdm.devices.traits."


def main() -> None:
    entries = json.loads((STORAGE / "core.config_entries").read_text())["data"]["entries"]
    nest = next(e for e in entries if e["domain"] == "nest")["data"]
    cred = json.loads((STORAGE / "application_credentials").read_text())["data"]["items"][0]

    body = urllib.parse.urlencode({
        "client_id": cred["client_id"],
        "client_secret": cred["client_secret"],
        "refresh_token": nest["token"]["refresh_token"],
        "grant_type": "refresh_token",
    }).encode()
    token = json.load(urllib.request.urlopen(urllib.request.Request("https://oauth2.googleapis.com/token", body)))
    print("token ok, expires_in", token["expires_in"])

    url = f"https://smartdevicemanagement.googleapis.com/v1/enterprises/{nest['project_id']}/devices"
    request = urllib.request.Request(url, headers={"Authorization": "Bearer " + token["access_token"]})
    for device in json.load(urllib.request.urlopen(request)).get("devices", []):
        t = device["traits"]
        print(
            device["type"].split(".")[-1],
            "mode", t.get(P + "ThermostatMode", {}).get("mode"),
            "heat_setpoint", t.get(P + "ThermostatTemperatureSetpoint", {}).get("heatCelsius"),
            "ambient", t.get(P + "Temperature", {}).get("ambientTemperatureCelsius"),
            "hvac", t.get(P + "ThermostatHvac", {}).get("status"),
        )


if __name__ == "__main__":
    main()
