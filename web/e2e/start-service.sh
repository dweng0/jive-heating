#!/usr/bin/env bash
# Starts a throwaway heating service for the browser tests: a fresh SQLite file, a Mosquitto
# container with three fake TRVZB valves and two fake room sensors (smoke alarms that report
# temperature), and the built web app. Playwright stops it afterwards.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$here/../.."
work="$(mktemp -d)"
port="${E2E_PORT:-18099}"
mqtt_port="${E2E_MQTT_PORT:-18831}"
# Another name (with other ports) lets a second checkout run its browser tests at the same time.
container="${E2E_MQTT_CONTAINER:-heating-e2e-mosquitto}"

docker rm -f "$container" >/dev/null 2>&1 || true
docker run -d --name "$container" -p "127.0.0.1:$mqtt_port:1883" eclipse-mosquitto:2 mosquitto -c /mosquitto-no-auth.conf >/dev/null
sleep 1
pub() { docker exec "$container" mosquitto_pub -r -t "$1" -m "$2"; }
pub zigbee2mqtt/bridge/devices '[{"friendly_name":"Lounge TRV","definition":{"model":"TRVZB"}},{"friendly_name":"Bedroom TRV","definition":{"model":"TRVZB"}},{"friendly_name":"Spare TRV","definition":{"model":"TRVZB"}},{"friendly_name":"Hall Smoke","definition":{"model":"SENSOR-1","exposes":[{"property":"temperature"},{"property":"battery"}]}},{"friendly_name":"Landing Smoke","definition":{"model":"SENSOR-1","exposes":[{"property":"temperature"},{"property":"battery"}]}}]'
pub "zigbee2mqtt/Lounge TRV" '{"local_temperature":19.0,"running_state":"idle","battery":90}'
pub "zigbee2mqtt/Bedroom TRV" '{"local_temperature":20.5,"running_state":"idle","battery":80}'
pub "zigbee2mqtt/Spare TRV" '{"local_temperature":18.0,"running_state":"idle","battery":70}'
pub "zigbee2mqtt/Hall Smoke" '{"temperature":21.3,"battery":95}'
pub "zigbee2mqtt/Landing Smoke" '{"temperature":17.2,"battery":15}'

(cd "$repo" && ./gradlew installDist -q --console=plain)
(cd "$repo/web" && npm run build --silent >/dev/null)

export HEATING_API_TOKEN=e2e-service-token
export MQTT_URL="tcp://127.0.0.1:$mqtt_port"
export HEATING_DB_PATH="$work/heating.db"
export WEB_DIR="$repo/web/dist"
export PORT="$port"
# A short minimum run and no minimum rest, so the boiler chip tests see it heat, finish its run and go idle quickly.
export BOILER_MIN_RUN_MINUTES=1
export BOILER_MIN_REST_MINUTES=0
exec "$repo/build/install/home-heating/bin/home-heating"
