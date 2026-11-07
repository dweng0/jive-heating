import { request } from './api'

/** GET /api/setup/status (openapi: SetupStatus). */
export interface SetupStatus {
  broker: 'missing' | 'connected' | 'disconnected'
  zigbee2mqtt: 'missing' | 'configured'
  complete: boolean
  /** When the broker connection dropped; only while disconnected */
  since?: string
  reason?: string
}

/** GET /api/setup/broker: never the password. */
export interface BrokerView {
  host: string
  port: number
  tls: boolean
  username: string | null
  passwordSet: boolean
}

/** What the Broker step has typed. A blank [password] on a saved broker means "keep it". */
export interface BrokerForm {
  host: string
  port: string
  tls: boolean
  username: string
  password: string
  /** Remove the saved password */
  clearPassword: boolean
}

export type BrokerTestResult = { ok: true } | { ok: false; reason: BrokerFailureReason; message: string }
export type BrokerFailureReason = 'unknown_host' | 'refused' | 'timeout' | 'not_authorized' | 'tls'

export interface BridgeFound {
  baseTopic: string
  version: string | null
  devices: number
}

/** The field a failed test points at. */
export type BrokerField = 'host' | 'port' | 'username' | 'password' | 'tls'

export function fieldFor(reason: BrokerFailureReason): BrokerField {
  switch (reason) {
    case 'unknown_host':
    case 'timeout':
      return 'host'
    case 'refused':
      return 'port'
    case 'not_authorized':
      return 'password'
    case 'tls':
      return 'tls'
  }
}

/** The request body for a form: the password is left out when untouched, which keeps the saved one; "" clears it. */
export function brokerBody(form: BrokerForm) {
  const body: Record<string, unknown> = { host: form.host.trim(), port: Number(form.port), tls: form.tls }
  if (form.username !== '') body.username = form.username
  if (form.clearPassword) body.password = ''
  else if (form.password !== '') body.password = form.password
  return body
}

/** Why the form cannot be sent, or null: host filled, port 1 to 65535. */
export function brokerFormError(form: BrokerForm): { field: BrokerField; message: string } | null {
  if (form.host.trim() === '') return { field: 'host', message: 'Enter the broker’s host name or address.' }
  const port = Number(form.port)
  if (!Number.isInteger(port) || port < 1 || port > 65535) return { field: 'port', message: 'Port must be from 1 to 65535.' }
  return null
}

/** "z2m (version 2.1.0, 14 devices)". */
export function bridgeLabel(b: BridgeFound): string {
  const devices = `${b.devices} ${b.devices === 1 ? 'device' : 'devices'}`
  return `${b.baseTopic} (${b.version ? `version ${b.version}, ` : ''}${devices})`
}

/** "Lost connection to your broker since 18:04". */
export function lostText(since: string | undefined, timeZone?: string): string {
  if (!since) return 'Lost connection to your broker'
  const time = new Date(since).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', timeZone })
  return `Lost connection to your broker since ${time}`
}

export const NO_BRIDGE_TEXT =
  "No Zigbee2MQTT found on this broker. Check it's running and connected to the same broker, or type its base topic."

export const setupApi = {
  status: () => request<SetupStatus>('GET', '/api/setup/status'),
  broker: () => request<BrokerView>('GET', '/api/setup/broker'),
  testBroker: (body: unknown) => request<BrokerTestResult>('POST', '/api/setup/broker/test', body),
  saveBroker: (body: unknown) => request<BrokerView>('PUT', '/api/setup/broker', body),
  discover: () => request<BridgeFound[]>('GET', '/api/setup/zigbee2mqtt/discover'),
  saveBaseTopic: (baseTopic: string) => request<{ baseTopic: string }>('PUT', '/api/setup/zigbee2mqtt', { baseTopic }),
  testNotification: () => request<{ sent: boolean; registered: boolean }>('POST', '/api/setup/test-notification'),
  complete: () => request<void>('POST', '/api/setup/complete'),
}
