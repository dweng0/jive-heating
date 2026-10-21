import { execSync } from 'node:child_process'

export default function teardown() {
  execSync(`docker rm -f ${process.env.E2E_MQTT_CONTAINER ?? 'heating-e2e-mosquitto'}`, { stdio: 'ignore' })
}
