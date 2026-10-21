// Energy prices, electricity meters and the energy dashboard. Mirrors the EnergySettings, Meter,
// EnergyDay and EnergyDashboard schemas in openapi.json. All admin only. Costs are in the currency's
// minor unit (pence).

import { request } from './api'
import { time } from './format'

export interface EnergySettings {
  gasPencePerKwh: number
  gasStandingPencePerDay: number
  boilerAverageKw: number
  electricityPencePerKwh: number
  electricityStandingPencePerDay: number
  electricityMeter: string | null
  /** ISO 4217 code every cost is in, e.g. "GBP" */
  currency: string
  notices: string[]
}

export type EnergyPrices = Omit<EnergySettings, 'electricityMeter' | 'currency' | 'notices'>

export interface Meter {
  name: string
  online: boolean
  /** Running total, kWh */
  energy: number | null
  /** Power now, W */
  power: number | null
  stale: boolean
  lastReading: string | null
  /** It is the house electricity meter */
  inUse: boolean
  /** Its use is monitored, as part of the house meter's (older services leave it out) */
  monitored?: boolean
  /** Id of the room this monitored device is in; null or left out when in no room */
  room?: string | null
}

export type EnergyPeriod = 'today' | 'week' | 'month'

export interface EnergyDay {
  date: string
  boilerHours: number
  gasKwh: number
  gasCost: number
  electricityKwh: number | null
  electricityCost: number | null
  standingCost: number
  totalCost: number
  missingMinutes: number
  incomplete: boolean
}

/** A monitored plug's use over the period: part of the house meter's electricity, "of which", never added. */
export interface DeviceEnergy {
  meter: string
  kwh: number
  cost: number
  /** Minutes the plug was stale: unknown, so kwh and cost are only so far */
  missingMinutes: number
  incomplete: boolean
  /** Id of the room the plug is in now, null when in no room */
  room: string | null
  /** Set while the plug is offline now: when it went. It is then also incomplete */
  offlineSince: string | null
  /** Set while it uses power but its energy total is not going up: when the total last changed. It is then also incomplete */
  totalStuckSince: string | null
}

/** A room's use over the period: the sum of its plugs, "of which" the house meter's electricity, never added. */
export interface RoomEnergy {
  room: string
  name: string
  kwh: number
  cost: number
  /** Minutes any of its plugs had no reading: unknown, so kwh and cost are only so far */
  missingMinutes: number
  incomplete: boolean
}

/** House electricity no monitored plug accounts for, never below 0; "of which", never added. */
export interface EverythingElse {
  kwh: number
  cost: number
  /** The longer of the house meter's and any plug's time with no reading */
  missingMinutes: number
  incomplete: boolean
}

export interface EnergyDashboard {
  period: EnergyPeriod
  from: string
  to: string
  boilerHours: number
  gasKwh: number
  gasCost: number
  electricityMeter: string | null
  electricityKwh: number | null
  electricityCost: number | null
  gasStandingCost: number
  electricityStandingCost: number | null
  standingCost: number
  totalCost: number
  missingMinutes: number
  incomplete: boolean
  days: EnergyDay[]
  /** ISO 4217 code the costs are in */
  currency: string
  /** Monitored devices, already part of electricityKwh and totalCost */
  devices: DeviceEnergy[]
  /** Rooms with monitored plugs, by name; part of devices, so never added to anything */
  rooms: RoomEnergy[]
  /** House electricity minus all monitored plugs; null without a house meter */
  everythingElse: EverythingElse | null
}

const enc = encodeURIComponent

export const energyApi = {
  settings: () => request<EnergySettings>('GET', '/api/settings/energy'),
  savePrices: (prices: Partial<EnergyPrices> & { currency?: string }) => request<EnergySettings>('PUT', '/api/settings/energy', prices),
  meters: (unassignedOnly = false) => request<Meter[]>('GET', unassignedOnly ? '/api/meters?unassigned=true' : '/api/meters'),
  useForHouse: (meter: string) => request<EnergySettings>('PUT', `/api/meters/${enc(meter)}/house`),
  stopUsingForHouse: (meter: string) => request<EnergySettings>('DELETE', `/api/meters/${enc(meter)}/house`),
  monitor: (meter: string) => request<Meter>('PUT', `/api/meters/${enc(meter)}/monitored`),
  stopMonitoring: (meter: string) => request<void>('DELETE', `/api/meters/${enc(meter)}/monitored`).then(() => true),
  putInRoom: (room: string, meter: string) => request<Meter>('PUT', `/api/rooms/${enc(room)}/meters/${enc(meter)}`),
  takeOutOfRoom: (room: string, meter: string) => request<void>('DELETE', `/api/rooms/${enc(room)}/meters/${enc(meter)}`).then(() => true),
  ignoreDevice: (device: string) => request<void>('PUT', `/api/devices/${enc(device)}/ignored`).then(() => true),
  ignoredDevices: () => request<string[]>('GET', '/api/devices/ignored'),
  showDeviceAgain: (device: string) => request<void>('DELETE', `/api/devices/${enc(device)}/ignored`).then(() => true),
  dashboard: (period: EnergyPeriod) => request<EnergyDashboard>('GET', `/api/dashboard?period=${period}`),
}

/** How the house meter is named to an admin: the button that picks it, its hint, and its role once chosen. */
export const MAIN_METER_BUTTON = 'Main meter (whole house)'
export const MONITOR_BUTTON = 'Monitor this device'

export const MONITOR_HINT = 'See what one appliance uses'

/** The room picker that follows "Monitor this device"; "Not now" leaves the plug in no room. */
export const roomPickerTitle = (meter: string) => `Which room is ${meter} in?`
export const ROOM_PICKER_HINT = "Its use then shows as part of that room's. Leave a shared plug in no room."
export const NOT_NOW = 'Not now'

export const MAIN_METER_HINT = 'Measures all electricity coming in'
export const MAIN_METER = 'Main meter'

/** What a meter is called in a list: the house meter is the "Main meter", any other is just an electricity meter. */
export function meterKind(m: Pick<Meter, 'inUse'>): string {
  return m.inUse ? MAIN_METER : 'Electricity meter'
}

/** The prices an admin edits, in order. `per` is null for the boiler rate, which is not money. */
export const PRICE_FIELDS: { key: keyof EnergyPrices; label: string; per: string | null }[] = [
  { key: 'gasPencePerKwh', label: 'Gas', per: 'kWh' },
  { key: 'gasStandingPencePerDay', label: 'Gas standing charge', per: 'day' },
  { key: 'electricityPencePerKwh', label: 'Electricity', per: 'kWh' },
  { key: 'electricityStandingPencePerDay', label: 'Electricity standing charge', per: 'day' },
  { key: 'boilerAverageKw', label: 'Boiler average', per: null },
]

/** The currencies an admin can choose; any other already set is offered by its code. */
export const CURRENCIES = [
  { code: 'GBP', label: '£ Pound (GBP)' },
  { code: 'EUR', label: '€ Euro (EUR)' },
  { code: 'USD', label: '$ US dollar (USD)' },
]

/** "€ Euro (EUR)", or the bare code for a currency not in the list. */
export const currencyLabel = (code: string) => CURRENCIES.find((c) => c.code === code)?.label ?? code

/** The list, plus [current] when it is another currency. */
export const currencyChoices = (current: string) =>
  CURRENCIES.some((c) => c.code === current) ? CURRENCIES : [...CURRENCIES, { code: current, label: current }]

export const NOT_CONVERTED = 'Prices are not converted. Check them for the new currency.'

/** Prices are entered in the currency's minor unit: "p" for pence, "c" for cents. */
export const minorUnit = (currency: string) => (currency === 'GBP' ? 'p' : 'c')

/** "p per kWh" in pounds, "c per kWh" in euros; "kW" for the boiler. */
export const priceUnit = (field: { per: string | null }, currency: string) => (field.per ? `${minorUnit(currency)} per ${field.per}` : 'kW')

/** "27.0p per kWh", "27.0c per kWh", "12.0 kW". */
export function formatPrice(value: number, field: { per: string | null }, currency: string): string {
  const unit = priceUnit(field, currency)
  return field.per ? `${value.toFixed(1)}${unit}` : `${value.toFixed(1)} ${unit}`
}

/** "£", "€", or the code itself when the browser does not know it. */
function symbol(currency: string): string {
  try {
    return new Intl.NumberFormat('en-GB', { style: 'currency', currency }).formatToParts(0).find((p) => p.type === 'currency')?.value ?? currency
  } catch {
    return currency
  }
}

/** Minor units as money in `currency`: "£6.92", "£0.96", "€1.25"; "–" when unknown. */
export function formatCost(pence: number | null | undefined, currency: string): string {
  if (pence == null) return '–'
  return `${symbol(currency)}${(pence / 100).toFixed(2)}`
}

export const formatKwh = (kwh: number | null | undefined) => (kwh == null ? '–' : `${kwh.toFixed(1)} kWh`)

/** "412 W", "1.2 kW". */
/** "Offline since 14:20. Cost will update when it's back.", the triangle's message on a plug offline now. */
export const offlineNow = (since: string) => `Offline since ${time(since)}. Cost will update when it's back.`

/** Shown while a plug uses power but its energy total is not going up. */
export const TOTAL_STUCK = "Using power but its energy total isn't going up. Cost may be too low."

/** The warning for a plug that is offline now or whose total is stuck; offline now wins (then there is no power to go by). */
export const plugNowWarning = (plug: { offlineSince: string | null; totalStuckSince: string | null }) =>
  plug.offlineSince ? offlineNow(plug.offlineSince) : plug.totalStuckSince ? TOTAL_STUCK : undefined

export function formatPower(watts: number | null | undefined): string {
  if (watts == null) return '–'
  return watts >= 1000 ? `${(watts / 1000).toFixed(1)} kW` : `${Math.round(watts)} W`
}

export const NO_METER = 'Choose an electricity meter to see electricity use'
