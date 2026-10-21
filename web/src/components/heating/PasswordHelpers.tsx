import { Checkbox } from '@/components/ui/checkbox'
import { cn } from '@/lib/utils'

/** The rule for a field, or what is wrong with it right now (in red). */
export function Hint({ problem, ok }: { problem: string | null; ok: string }) {
  return <p className={cn('text-xs', problem ? 'text-destructive' : 'text-muted-foreground')}>{problem ?? ok}</p>
}

/** "Show password" for every password field in a form; the whole label is clickable. */
export function ShowPassword({ checked, onChange, label = 'Show password' }: { checked: boolean; onChange: (v: boolean) => void; label?: string }) {
  return (
    <label className="flex min-h-9 cursor-pointer items-center gap-2.5 text-sm select-none">
      <Checkbox checked={checked} onCheckedChange={(v) => onChange(v === true)} />
      {label}
    </label>
  )
}
