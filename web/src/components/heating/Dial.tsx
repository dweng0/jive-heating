import type { ReactNode } from 'react'
import { MAX_TARGET, MIN_TARGET, formatTemp } from '@/lib/format'
import { cn } from '@/lib/utils'

const START = 135 // degrees, bottom-left
const SWEEP = 270

const angleFor = (t: number) => START + (SWEEP * (Math.min(MAX_TARGET, Math.max(MIN_TARGET, t)) - MIN_TARGET)) / (MAX_TARGET - MIN_TARGET)

function point(angle: number, r: number) {
  const rad = (angle * Math.PI) / 180
  return { x: 100 + r * Math.cos(rad), y: 100 + r * Math.sin(rad) }
}

function arc(from: number, to: number, r: number) {
  const a = point(from, r)
  const b = point(to, r)
  const large = to - from > 180 ? 1 : 0
  return `M ${a.x} ${a.y} A ${r} ${r} 0 ${large} 1 ${b.x} ${b.y}`
}

/**
 * The room's thermostat: target as a warm arc, current temperature as a dot, big target number in the middle.
 * [currentInfo] sits beside the current temperature (e.g. a note that a room sensor measures it).
 */
export function Dial({
  target,
  current,
  heating,
  off,
  label,
  currentInfo,
}: {
  target: number | null
  current: number | null
  heating: boolean
  off: boolean
  label: string
  currentInfo?: ReactNode
}) {
  const targetAngle = target == null ? START : angleFor(target)
  const currentPoint = current == null ? null : point(angleFor(current), 84)
  return (
    <div className="relative mx-auto aspect-square w-full max-w-72">
      <svg viewBox="0 0 200 200" className="size-full" role="img" aria-label={`${label}: target ${formatTemp(target)}, now ${formatTemp(current)}`}>
        <defs>
          <linearGradient id="dial-heat" x1="0" y1="1" x2="1" y2="0">
            <stop offset="0%" stopColor="var(--chart-2)" />
            <stop offset="100%" stopColor="var(--heat)" />
          </linearGradient>
        </defs>
        <path d={arc(START, START + SWEEP, 84)} fill="none" stroke="var(--secondary)" strokeWidth="14" strokeLinecap="round" />
        {!off && target != null && (
          <path d={arc(START, Math.max(START + 0.01, targetAngle), 84)} fill="none" stroke="url(#dial-heat)" strokeWidth="14" strokeLinecap="round" />
        )}
        {currentPoint && <circle cx={currentPoint.x} cy={currentPoint.y} r="6" fill="var(--card)" stroke="var(--foreground)" strokeWidth="2.5" />}
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center">
        <div className="text-sm font-medium text-muted-foreground">{off ? 'Off' : heating ? 'Heating to' : 'Target'}</div>
        <div className={cn('text-6xl font-bold tracking-tighter tabular-nums', heating && 'text-heat-text', off && 'text-muted-foreground')}>
          {off ? '—' : formatTemp(target)}
        </div>
        <div className="mt-1 flex items-center gap-0.5 text-sm text-muted-foreground tabular-nums">
          <span>now {formatTemp(current)}</span>
          {currentInfo}
        </div>
      </div>
    </div>
  )
}
