<p align="center">
  <img src="docs/logo.svg" alt="Jive Heating: a smiling fire elemental" width="180">
</p>

<h1 align="center">Jive Heating</h1>

[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Ktor](https://img.shields.io/badge/Ktor-3.1-087CFA?logo=ktor&logoColor=white)](https://ktor.io)
[![JVM](https://img.shields.io/badge/JVM-17-ED8B00?logo=openjdk&logoColor=white)](https://adoptium.net)
[![Android](https://img.shields.io/badge/Android-13%2B-3DDC84?logo=android&logoColor=white)](android/)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/compose)
[![Zigbee2MQTT](https://img.shields.io/badge/Zigbee2MQTT-TRVZB-FFC135?logo=zigbee&logoColor=black)](https://www.zigbee2mqtt.io)
[![Home Assistant](https://img.shields.io/badge/Home%20Assistant-Thermostat-18BCF2?logo=homeassistant&logoColor=white)](https://www.home-assistant.io)
[![Docker](https://img.shields.io/badge/Docker-ready-2496ED?logo=docker&logoColor=white)](Dockerfile)
[![BDD](https://img.shields.io/badge/BDD%20scenarios-84%2F84-brightgreen)](BDD_STATUS.md)
[![Built with BAADD](https://img.shields.io/badge/built%20with-BAADD-ff6f61)](https://github.com/dweng0/POPPINS)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**Room-by-room smart heating that you own.** Put a SONOFF TRVZB valve on each radiator, and Jive
Heating gives every room its own schedule, boost and manual mode, then tells your boiler when to
fire. It runs on a box in your house, with a web app and an Android app on top. No cloud account, no
subscription.

```
 Web app / Android app ──HTTPS + sign-in──▶ Jive Heating service ──MQTT──▶ Zigbee2MQTT ──Zigbee──▶ TRVZB valves
                                                   │
                                                   └── heating/demand ──▶ Home Assistant ──▶ thermostat ──▶ boiler
```

## Features

- **Rooms** with one or more valves, controlled as one zone (average temperature) or a zone per valve
- **Room sensors**: use a Zigbee sensor (e.g. a smoke alarm that reports temperature) as the room thermometer
- **Weekly schedules** per zone, with copy-to-other-days
- **Modes**: schedule, manual, off (frost protection)
- **Boost** for 30-180 minutes, mirrored to the valve's own timer mode
- **Holiday**: one switch for the whole house, a set temperature until a set date and time
- **Groups**: set several rooms at once ("Upstairs", "Kids")
- **Knob turns** on a valve override the schedule until the next block, like commercial smart thermostats
- **Boiler demand** with hysteresis plus minimum run and rest times, so the boiler never short-cycles
- **People**: an admin creates accounts and chooses which rooms each person can see and change
- **Phone notifications** ("someone turned the heating on", "heating finished") via Firebase and Web Push
- **Energy**: electricity use per device and per room, with prices and a currency of your choice
- **Activity feed** of who changed what, and when
- **Live updates** over WebSocket; SQLite storage that survives restarts

## What you need

| | |
|---|---|
| A computer that is always on | A Raspberry Pi, mini PC or home server. Docker is the easy way to run the service |
| [Mosquitto](https://mosquitto.org) | The MQTT broker |
| [Zigbee2MQTT](https://www.zigbee2mqtt.io) | With a Zigbee coordinator, and your TRVZB valves paired |
| [Home Assistant](https://www.home-assistant.io) | Only to switch the boiler. It listens for the demand message and sets your thermostat |
| An Android 13+ phone | Optional. The web app works on any phone or computer |

## Quick start

### 1. Get the code and configure it

```bash
git clone https://github.com/dweng0/jive-heating.git
cd jive-heating
cp .env.example .env
```

Edit `.env`. At minimum set `HEATING_API_TOKEN` to a long random value. Generate one with
`openssl rand -hex 32`. This token is your admin key: keep it secret.

| Variable | Default | What it does |
|---|---|---|
| `HEATING_API_TOKEN` | **required** | Service token. Used once to create the first admin, and by scripts |
| `MQTT_URL` | `tcp://mosquitto:1883` | Where your Mosquitto broker is |
| `Z2M_BASE_TOPIC` | `zigbee2mqtt` | Zigbee2MQTT base topic |
| `HEATING_TIMEZONE` | `Europe/London` | Schedule time zone, an [IANA name](https://en.wikipedia.org/wiki/List_of_tz_database_time_zones) like `America/New_York` |
| `HEATING_DB_PATH` | `/data/heating.db` | SQLite file |
| `BOILER_MIN_RUN_MINUTES` | `4` | Once on, the boiler stays on at least this long |
| `BOILER_MIN_REST_MINUTES` | `5` | Once off, the boiler stays off at least this long |
| `KUMA_PUSH_URL` | unset | [Uptime Kuma](https://github.com/louislam/uptime-kuma) push monitor, to alert you when a valve goes offline |
| `FCM_SERVICE_ACCOUNT_FILE` | unset | Firebase service-account key, for Android push notifications (see below) |
| `WEBPUSH_CONTACT` | unset | `mailto:` or `https:` address, to enable browser notifications |

### 2. Build and run the service

The Docker build compiles the service and the web app together.

```bash
docker build -t jive-heating .
mkdir -p data && sudo chown 10001 data     # the container runs as uid 10001
docker run -d --name jive-heating --restart unless-stopped --env-file .env \
  -v "$PWD/data:/data" -p 127.0.0.1:8098:8080 jive-heating
curl localhost:8098/health                 # {"status":"ok"}
```

If Mosquitto runs in Docker too, put both containers on the same network (add
`--network <your-network>`) so `tcp://mosquitto:1883` resolves. If it runs on the host, set
`MQTT_URL=tcp://host.docker.internal:1883` (add `--add-host=host.docker.internal:host-gateway` on Linux)
or its LAN address.

Or run it without Docker (needs JDK 17):

```bash
(cd web && npm ci && npm run build)
HEATING_API_TOKEN=... MQTT_URL=tcp://localhost:1883 WEB_DIR=web/dist ./gradlew run
```

### 3. Create the first admin

Open `http://localhost:8098` (or your server's address). On first launch the app asks for the
service token from your `.env`, plus a username and password for the admin account. After that you sign
in with the username and password, and can add other people under **Account → People**.

### 4. Add your valves and rooms

1. Pair the TRVZB valves in Zigbee2MQTT. Jive Heating discovers them automatically.
2. In the app, create a room and assign its valve (or valves).
3. Set a schedule, or just turn the room to manual.

Plugging in a Zigbee sensor that reports temperature lets you assign it to a room as the thermometer.

### 5. Connect your boiler

The service publishes whether any room wants heat, retained, to the MQTT topic `heating/demand`:

```json
{"demand": true}
```

Make your boiler follow it with a Home Assistant automation. This example sets a thermostat (replace
`climate.my_thermostat` with yours) to 21 °C on demand and 10 °C otherwise:

```yaml
automation:
  - alias: Jive Heating boiler demand
    trigger:
      - platform: mqtt
        topic: heating/demand
    action:
      - service: climate.set_temperature
        target:
          entity_id: climate.my_thermostat
        data:
          temperature: "{{ 21 if trigger.payload_json.demand else 10 }}"
```

Any other way of acting on that message works too, such as a relay wired to the boiler.

### 6. Put it on your phone

**Web app**: open the address in a phone browser and "Add to Home Screen". It installs like an app.

**Android app**:

```bash
cd android && ./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Enter your service's address and sign in on first launch. To let people download it from the web app
(**Account → Android app**), copy the APK to `android-dist/heating.apk` before building the Docker image.

## Going outside your home network

The service speaks plain HTTP and expects a reverse proxy to provide HTTPS. Pick one:

- **Stay on your LAN or VPN** (Tailscale, WireGuard): simplest and safest. Nothing is exposed.
- **A reverse proxy with HTTPS** (Caddy, nginx, Traefik) or a tunnel (e.g. Cloudflare Tunnel) in front of
  `127.0.0.1:8098`. WebSockets (`/ws`) must be allowed through. The app throttles repeated failed
  sign-ins, but put your own rate limit on `/api/auth/login` too.

The Android app only allows cleartext HTTP to `localhost`, so use HTTPS for anything remote.

## Optional extras

**Phone push notifications (Android).** Create a Firebase project, enable Cloud Messaging, download the
`google-services.json` into `android/app/` before building the app (it is gitignored), create a service
account key, mount that JSON into the container and set `FCM_SERVICE_ACCOUNT_FILE` to its path. Without
it, everything works except push notifications.

**Browser notifications (Web Push).** Set `WEBPUSH_CONTACT=mailto:you@example.com`. The service creates
and stores its own signing keys.

**Boiler watchdog.** `scripts/nest_watchdog.py` is a fail-safe for Nest + Home Assistant setups: if the
heating service or Home Assistant dies, it drops the Nest to its off setpoint. See the header of the
script for how to run it from cron.

## Layout

| Path | What |
|---|---|
| `src/` | Ktor service. Domain glossary and package map in [`CONTEXT.md`](CONTEXT.md) |
| `src/main/resources/openapi.json` | API contract shared by the service and the apps |
| `web/` | Web app (React, TypeScript, Vite, Tailwind) |
| `android/` | Native Android app (Kotlin, Compose, Material 3) |
| `BDD.md` | The spec: every behaviour is a scenario with a test |
| `BDD_STATUS.md` | Which scenarios are covered by tests |

## Developing

```bash
./gradlew test                          # service: one test per BDD scenario
cd android && ./gradlew test            # Android unit tests
cd web && npm ci && npm run dev         # web app with hot reload, proxies the API to localhost:8098
cd web && npx playwright test           # browser tests (needs Docker for a throwaway Mosquitto)
python3 scripts/check_bdd_coverage.py BDD.md
```

`HEATING_API=http://other-host:8098 npm run dev` points the web dev server at a different service.

## How it was built

Spec first, with [POPPINS](https://github.com/dweng0/POPPINS) (Behaviour and AI Driven
Development): every feature starts as a scenario in `BDD.md`, and AI agents implement them
test-first. Want a feature? Add a scenario to `BDD.md` and open a pull request.

## License

[MIT](LICENSE). The bundled Plus Jakarta Sans font is under the
[SIL Open Font License](android/FONT_LICENSE_OFL.txt).
