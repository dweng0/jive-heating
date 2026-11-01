import { useState } from 'react'
import { ArrowDown, ArrowUp } from 'lucide-react'
import { IncompleteWarning } from '@/components/heating/IncompleteWarning'
import { Card, CardContent } from '@/components/ui/card'
import { formatCost, formatKwh, formatPower } from '@/lib/energy'
import { FIRST_SORT, movedOut, nextSort, plugShare, plugState, sortPlugs, type PlugColumn, type PlugSort } from '@/lib/energyUsage'
import { plugWarning, type RoomPlugs } from '@/lib/roomPlugs'
import { cn } from '@/lib/utils'

const COLUMNS: { key: PlugColumn; label: string }[] = [
  { key: 'meter', label: 'Plug' },
  { key: 'state', label: 'State' },
  { key: 'power', label: 'Power now' },
  { key: 'share', label: 'Share today' },
  { key: 'kwh', label: 'Use today' },
  { key: 'cost', label: 'Cost today' },
]

const STICKY = 'sticky left-0 bg-card'

/** Each plug in a room, sortable by any heading. Picking a plug's name shows its chart. Moved-out use stays last, so it all adds up. */
export function PlugTable({ plugs, onPick }: { plugs: RoomPlugs; onPick: (meter: string) => void }) {
  const [sort, setSort] = useState<PlugSort>(FIRST_SORT)
  const left = movedOut(plugs)
  return (
    <Card>
      <CardContent className="overflow-x-auto p-0">
        <table data-testid="plug-table" className="w-full min-w-xl text-sm tabular-nums">
          <thead>
            <tr className="border-b text-muted-foreground">
              {COLUMNS.map((c, i) => (
                <th key={c.key} scope="col" aria-sort={sort.column !== c.key ? 'none' : sort.descending ? 'descending' : 'ascending'} className={cn('p-0 font-medium', i === 0 ? STICKY : 'text-right')}>
                  <button type="button" onClick={() => setSort(nextSort(sort, c.key))} className={cn('inline-flex w-full items-center gap-1 px-4 py-3 hover:text-foreground', i !== 0 && 'justify-end')}>
                    {c.label}
                    {sort.column === c.key && (sort.descending ? <ArrowDown className="size-3.5" /> : <ArrowUp className="size-3.5" />)}
                  </button>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {sortPlugs(plugs.plugs, sort).map((plug) => {
              const share = plugShare(plug, plugs)
              return (
                <tr key={plug.meter} data-testid="plug-row" className="border-b last:border-0">
                  <th scope="row" className={cn('px-4 py-3 text-left font-medium', STICKY)}>
                    <button type="button" className="text-left underline-offset-2 hover:underline" onClick={() => onPick(plug.meter)}>{plug.meter}</button>
                  </th>
                  <td data-testid="plug-state" className="px-4 py-3 text-right">{plugState(plug)}</td>
                  <td data-testid="plug-power" className="px-4 py-3 text-right">{plug.offlineSince ? 'Offline' : formatPower(plug.power)}</td>
                  <td className="px-4 py-3 text-right">{share == null ? '–' : `${share}%`}</td>
                  <td className="px-4 py-3 text-right">{formatKwh(plug.kwh)}</td>
                  <td className="px-4 py-3 text-right font-semibold">
                    <span className="inline-flex items-center justify-end gap-1.5">
                      {plug.incomplete && <IncompleteWarning missingMinutes={plug.missingMinutes} message={plugWarning(plug)} />}
                      {formatCost(plug.cost, plugs.currency)}
                    </span>
                  </td>
                </tr>
              )
            })}
            {left && (
              <tr data-testid="plug-moved-out" className="text-muted-foreground">
                <th scope="row" className={cn('px-4 py-3 text-left font-medium', STICKY)}>Moved out</th>
                <td className="px-4 py-3 text-right">–</td>
                <td className="px-4 py-3 text-right">–</td>
                <td className="px-4 py-3 text-right">{plugs.kwh > 0 ? `${Math.round((left.kwh / plugs.kwh) * 100)}%` : '–'}</td>
                <td className="px-4 py-3 text-right">{formatKwh(left.kwh)}</td>
                <td className="px-4 py-3 text-right font-semibold">{formatCost(left.cost, plugs.currency)}</td>
              </tr>
            )}
          </tbody>
        </table>
      </CardContent>
    </Card>
  )
}
