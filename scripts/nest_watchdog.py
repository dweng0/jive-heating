#!/usr/bin/env python3
"""Fail-safe: drop the Nest to its off setpoint if Home Assistant or the heating service is dead.

Runs from cron every minute, independent of HA. If either dependency fails FAILS_BEFORE_ACT checks in
a row and the Nest is calling for heat above OFF_SETPOINT, it sets the heat setpoint to OFF_SETPOINT
through the Google SDM API (credentials read from HA's .storage at runtime, never copied or logged).

It fires at most once per outage: after acting it leaves the Nest alone (someone may set it by hand)
until both dependencies have been healthy again. Every action and every state change is appended as a JSON line to the log file. If KUMA_WATCHDOG_URL
is set, status is pushed to that Uptime Kuma push monitor on each run.

Usage: nest_watchdog.py [--dry-run]
Env:   HA_STORAGE (default ~/projects/home-lab/homeassistant/.storage)
       HEATING_URL (default http://localhost:8098), HA_URL (default http://localhost:8123)
       WATCHDOG_DIR (default ~/.local/state/nest-watchdog)
       KUMA_WATCHDOG_URL (optional push URL)
"""
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

HOME = Path.home()
STORAGE = Path(os.environ.get("HA_STORAGE", HOME / "projects/home-lab/homeassistant/.storage"))
HEATING_URL = os.environ.get("HEATING_URL", "http://localhost:8098")
HA_URL = os.environ.get("HA_URL", "http://localhost:8123")
WORK_DIR = Path(os.environ.get("WORK_DIR", os.environ.get("WATCHDOG_DIR", HOME / ".local/state/nest-watchdog")))
KUMA_URL = os.environ.get("KUMA_WATCHDOG_URL", "")

OFF_SETPOINT = 10.0
FAILS_BEFORE_ACT = 3
LOG = WORK_DIR / "watchdog.log"
STATE = WORK_DIR / "watchdog.state.json"
P = "sdm.devices.traits."


def log(event: str, **fields) -> None:
    line = {"ts": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "event": event, **fields}
    WORK_DIR.mkdir(parents=True, exist_ok=True)
    with LOG.open("a") as f:
        f.write(json.dumps(line) + "\n")
    print(json.dumps(line))


def alive(url: str) -> bool:
    """Any HTTP response (even 401/302) means the process is up; connection errors and 5xx do not."""
    try:
        urllib.request.urlopen(url, timeout=5)
        return True
    except urllib.error.HTTPError as e:
        return e.code < 500
    except Exception:
        return False


def nest_access():
    entries = json.loads((STORAGE / "core.config_entries").read_text())["data"]["entries"]
    nest = next(e for e in entries if e["domain"] == "nest")["data"]
    cred = json.loads((STORAGE / "application_credentials").read_text())["data"]["items"][0]
    body = urllib.parse.urlencode({
        "client_id": cred["client_id"],
        "client_secret": cred["client_secret"],
        "refresh_token": nest["token"]["refresh_token"],
        "grant_type": "refresh_token",
    }).encode()
    token = json.load(urllib.request.urlopen(urllib.request.Request("https://oauth2.googleapis.com/token", body), timeout=15))
    return nest["project_id"], token["access_token"]


def sdm(project: str, access: str, path: str = "", payload: dict | None = None):
    url = f"https://smartdevicemanagement.googleapis.com/v1/enterprises/{project}/devices{path}"
    data = json.dumps(payload).encode() if payload is not None else None
    headers = {"Authorization": "Bearer " + access}
    if data:
        headers["Content-Type"] = "application/json"
    return json.load(urllib.request.urlopen(urllib.request.Request(url, data, headers), timeout=15))


def push_kuma(status: str, msg: str) -> None:
    if not KUMA_URL:
        return
    sep = "&" if "?" in KUMA_URL else "?"
    try:
        urllib.request.urlopen(f"{KUMA_URL}{sep}status={status}&msg={urllib.parse.quote(msg)}", timeout=5)
    except Exception:
        pass


def main() -> int:
    dry_run = "--dry-run" in sys.argv
    problems = []
    if not alive(f"{HEATING_URL}/health"):
        problems.append("heating-service")
    if not alive(HA_URL):
        problems.append("home-assistant")

    state = json.loads(STATE.read_text()) if STATE.exists() else {"fails": 0, "acted": False}
    if not problems:
        if state["fails"]:
            log("recovered", after_fails=state["fails"])
        state = {"fails": 0, "acted": False}
        if not dry_run:
            STATE.parent.mkdir(parents=True, exist_ok=True)
            STATE.write_text(json.dumps(state))
        push_kuma("up", "ok")
        return 0

    state["fails"] += 1
    log("unhealthy", problems=problems, consecutive=state["fails"])
    push_kuma("down", "unhealthy: " + ", ".join(problems))

    if state["fails"] >= FAILS_BEFORE_ACT and not state["acted"]:
        try:
            project, access = nest_access()
            devices = sdm(project, access).get("devices", [])
            for device in devices:
                traits = device["traits"]
                setpoint = traits.get(P + "ThermostatTemperatureSetpoint", {}).get("heatCelsius")
                mode = traits.get(P + "ThermostatMode", {}).get("mode")
                if mode == "HEAT" and setpoint is not None and setpoint > OFF_SETPOINT + 0.5:
                    if dry_run:
                        log("would_set_nest_off", setpoint=setpoint, device=device["name"].split("/")[-1][:8])
                    else:
                        sdm(project, access, f"/{device['name'].split('/')[-1]}:executeCommand", {
                            "command": P.replace("traits.", "commands.") + "ThermostatTemperatureSetpoint.SetHeat",
                            "params": {"heatCelsius": OFF_SETPOINT},
                        })
                        log("set_nest_off", previous_setpoint=setpoint, problems=problems)
                    state["acted"] = True
        except Exception as e:  # never let the watchdog crash silently
            log("nest_action_failed", error=type(e).__name__, detail=str(e)[:200])
        else:
            state["acted"] = True  # fire once per outage: later manual changes are left alone

    if not dry_run:
        STATE.parent.mkdir(parents=True, exist_ok=True)
        STATE.write_text(json.dumps(state))
    return 1


if __name__ == "__main__":
    sys.exit(main())
