import type { ActivityEvent } from './api'

/** "Utility Room" becomes "Utility": phones drop the generic word and let CSS ellipsis do the rest. */
export const shortRoom = (name: string) => name.replace(/\s+room$/i, '') || name

const degrees = (v: ActivityEvent['value']) => `${v}°`

const joined = (names: string[]) => (names.length <= 1 ? (names[0] ?? '') : `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`)

/** One line of the activity tray: [wide] for wide screens, [narrow] for phones, and the full wording for screen readers. */
export interface ActivityLine {
  wide: string
  narrow: string
}

/**
 * Words an event. Wide is a sentence; narrow keeps to a few words so it fits one line on a phone,
 * with the actor's name first when a person did it.
 */
export function activityLine(e: ActivityEvent): ActivityLine {
  const who = e.actor
  const room = e.room ?? ''
  const short = shortRoom(room)
  const house = room === 'Whole house'
  const target = typeof e.value === 'number' ? degrees(e.value) : ''
  switch (e.kind) {
    case 'setpoint':
      return house
        ? { wide: `${who} set the whole house to ${target}`, narrow: `${who} · House ${target}` }
        : { wide: `${who} turned the ${room} thermostat to ${target}`, narrow: `${who} · ${short} ${target}` }
    case 'mode': {
      const name = house ? 'the whole house' : room
      const wording = e.value === 'off' ? 'off' : e.value === 'schedule' ? 'Auto' : String(e.value)
      return {
        wide: e.value === 'off' ? `${who} turned ${name} off` : `${who} set ${name} to ${wording}`,
        narrow: `${who} · ${house ? 'House' : short} ${wording.toLowerCase()}`,
      }
    }
    case 'boost':
      if (typeof e.value === 'number') {
        return { wide: `${who} boosted ${room} to ${target}`, narrow: `${who} · ${short} boost ${target}` }
      }
      return who
        ? { wide: `${who} cancelled the boost in ${room}`, narrow: `${who} · ${short} boost off` }
        : { wide: `The boost in ${room} ended`, narrow: `${short} boost ended` }
    case 'holiday': {
      const start = e.value === 'start'
      if (!who) return start ? { wide: 'Holiday started', narrow: 'Holiday on' } : { wide: 'Holiday ended', narrow: 'Holiday over' }
      return start ? { wide: `${who} started a holiday`, narrow: `${who} · Holiday on` } : { wide: `${who} ended the holiday`, narrow: `${who} · Holiday over` }
    }
    case 'knob':
      return e.value === 'off'
        ? { wide: `The ${room} thermostat was turned off`, narrow: `${short} knob off` }
        : { wide: `The ${room} thermostat was turned to ${target}`, narrow: `${short} knob ${target}` }
    case 'schedule':
      return typeof e.value === 'number'
        ? { wide: `Schedule set ${room} to ${target}`, narrow: `${short} ${target} (schedule)` }
        : { wide: `Schedule turned ${room} off`, narrow: `${short} off (schedule)` }
    case 'boiler':
      return boilerLine(e)
    case 'offline':
      if (e.device) {
        const where = e.room ? `${e.device} in ${e.room}` : e.device
        return e.value === 'offline'
          ? { wide: `${where} went offline`, narrow: `${e.device} offline` }
          : { wide: `${where} is back online`, narrow: `${e.device} online` }
      }
      return e.value === 'offline'
        ? { wide: `${room} went offline`, narrow: `${short} offline` }
        : { wide: `${room} is back online`, narrow: `${short} online` }
    case 'override':
      return e.value === 'off'
        ? { wide: `${who ?? 'An admin'} turned the boiler off`, narrow: 'Boiler turned off' }
        : { wide: `${who ?? 'An admin'} turned the boiler back on`, narrow: 'Boiler back on' }
    case 'unresponsive':
      return e.value === 'unresponsive'
        ? { wide: `${room} is not responding to commands`, narrow: `${short} not responding` }
        : { wide: `${room} is responding again`, narrow: `${short} responding` }
    default:
      return { wide: `${who ?? 'Something'} changed ${room || 'the heating'}`, narrow: `${short || 'Heating'} changed` }
  }
}

function boilerLine(e: ActivityEvent): ActivityLine {
  const rooms = e.rooms
  switch (e.value) {
    case 'heating':
      return { wide: 'Boiler is now heating', narrow: 'Boiler heating' }
    case 'cycling':
      if (e.reason === 'reached-temp' && rooms.length > 0) {
        return {
          wide: `${joined(rooms)} ${rooms.length === 1 ? 'has' : 'have'} reached temperature, boiler cycling down`,
          narrow: `${joined(rooms.map(shortRoom))} at temp`,
        }
      }
      return { wide: 'Boiler finishing its minimum run', narrow: 'Boiler finishing run' }
    case 'resting':
      return { wide: 'Boiler resting, waiting out its minimum rest', narrow: 'Boiler resting' }
    case 'cycled':
      return { wide: `Boiler cycled ${e.count} times`, narrow: `Boiler cycled ×${e.count}` }
    default:
      return { wide: 'Boiler is now idle', narrow: 'Boiler idle' }
  }
}

/** The full wording, for screen readers and tooltips. */
export const activityLabel = (e: ActivityEvent) => activityLine(e).wide

/** "now", "5m ago", "2h ago", "yesterday", then the date. [now] is ms. */
export function ago(at: string, now: number): string {
  const seconds = Math.max(0, Math.floor((now - Date.parse(at)) / 1000))
  if (seconds < 60) return 'now'
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}h ago`
  const days = Math.floor((startOfDay(now) - startOfDay(Date.parse(at))) / 86_400_000)
  if (days <= 1) return 'yesterday'
  return new Date(at).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })
}

const startOfDay = (ms: number) => {
  const d = new Date(ms)
  d.setHours(0, 0, 0, 0)
  return d.getTime()
}
