import { Info, WifiOff } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { cn } from '@/lib/utils'

export const SENSOR_OFFLINE = 'Sensor offline — using valve readings'

/**
 * A small info button next to a temperature measured by a room sensor. Opens (click, tap,
 * Enter or Space) a short note on what that means; Escape or a tap elsewhere closes it.
 */
export function SensorInfo({ sensor, className }: { sensor: string; className?: string }) {
  return (
    <Popover>
      <PopoverTrigger
        aria-label="About this temperature"
        className={cn(
          'inline-flex size-6 shrink-0 items-center justify-center rounded-full text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none',
          className,
        )}
      >
        <Info className="size-4" />
      </PopoverTrigger>
      <PopoverContent className="flex flex-col gap-2 text-left">
        <p className="font-semibold">External thermometer</p>
        <p className="text-muted-foreground">
          This room’s temperature comes from a separate thermometer ({sensor}), not the radiator valves. Its readings can lag a few minutes
          behind the room, and the valves work from the last reading received. If it stops reporting, the room goes back to the valves’ own
          readings.
        </p>
      </PopoverContent>
    </Popover>
  )
}

/** Shown while a room's sensor is offline or silent and its valves measure it instead. */
export function SensorOfflineBadge({ className }: { className?: string }) {
  return (
    <Badge variant="secondary" className={cn('h-auto bg-danger whitespace-normal text-danger-foreground', className)}>
      <WifiOff /> {SENSOR_OFFLINE}
    </Badge>
  )
}
