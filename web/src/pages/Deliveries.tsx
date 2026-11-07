import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { ArrowDown, ArrowUp, ChevronLeft } from 'lucide-react'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Card, CardContent } from '@/components/ui/card'
import { deliveriesApi, statusLabel, type Deliveries, type Delivery, type DeliverySort, type DeliveryStatus } from '@/lib/deliveries'
import { dateTime } from '@/lib/format'

const RANGES = [
  { value: '', label: 'Any time', hours: 0 },
  { value: '1h', label: 'Last hour', hours: 1 },
  { value: '24h', label: 'Last 24 hours', hours: 24 },
  { value: '7d', label: 'Last 7 days', hours: 24 * 7 },
  { value: '30d', label: 'Last 30 days', hours: 24 * 30 },
]

const COLUMNS: { sort: DeliverySort; label: string }[] = [
  { sort: 'time', label: 'Time' },
  { sort: 'rule', label: 'Rule' },
  { sort: 'person', label: 'Person' },
  { sort: 'status', label: 'Status' },
  { sort: 'tries', label: 'Tries' },
]

/** Settings > Notifications > Deliveries: every notification, newest first, filtered with chips and sortable by column. */
export function DeliveriesPage() {
  const [data, setData] = useState<Deliveries | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [status, setStatus] = useState('')
  const [rule, setRule] = useState('')
  const [person, setPerson] = useState('')
  const [range, setRange] = useState('')
  const [sort, setSort] = useState<DeliverySort>('time')
  const [dir, setDir] = useState<'asc' | 'desc'>('desc')
  const [open, setOpen] = useState<Delivery | null>(null)

  useEffect(() => {
    let cancelled = false
    const hours = RANGES.find((r) => r.value === range)?.hours ?? 0
    deliveriesApi
      .list({
        status: (status || undefined) as DeliveryStatus | undefined,
        rule: rule || undefined,
        person: person || undefined,
        from: hours ? new Date(Date.now() - hours * 3600_000).toISOString() : undefined,
        sort,
        dir,
      })
      .then(
        (d) => !cancelled && (setData(d), setError(null)),
        (e: Error) => !cancelled && setError(e.message),
      )
    return () => {
      cancelled = true
    }
  }, [status, rule, person, range, sort, dir])

  const sources = useMemo(() => data?.sources ?? [], [data])

  function sortBy(column: DeliverySort) {
    if (column === sort) setDir(dir === 'asc' ? 'desc' : 'asc')
    else {
      setSort(column)
      setDir(column === 'time' ? 'desc' : 'asc')
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <div>
        <Link to="/account" className="inline-flex items-center gap-1 text-sm text-muted-foreground">
          <ChevronLeft className="size-4" /> Settings
        </Link>
        <h1 className="text-2xl font-semibold">Deliveries</h1>
      </div>

      <div className="flex flex-wrap gap-2" data-testid="delivery-chips">
        <Chip label="Status" value={status} onChange={setStatus} options={[['', 'Any'], ...(['queued', 'sent', 'retrying', 'failed'] as const).map((s) => [s, statusLabel[s]] as [string, string])]} />
        <Chip label="Rule" value={rule} onChange={setRule} options={[['', 'Any'], ...sources.map((s) => [s, s] as [string, string])]} />
        <Chip label="Person" value={person} onChange={setPerson} options={[['', 'Anyone'], ...(data?.people ?? []).map((p) => [p.name, p.name] as [string, string])]} />
        <Chip label="Time range" value={range} onChange={setRange} options={RANGES.map((r) => [r.value, r.label])} />
      </div>

      {error && <p className="text-sm text-destructive">{error}</p>}
      <Card>
        <CardContent className="overflow-x-auto p-0">
          <table className="w-full text-sm" data-testid="deliveries-table">
            <thead>
              <tr className="border-b text-left text-muted-foreground">
                {COLUMNS.map((c) => (
                  <th key={c.sort} className="p-3 font-medium" aria-sort={sort === c.sort ? (dir === 'asc' ? 'ascending' : 'descending') : 'none'}>
                    <button type="button" className="inline-flex items-center gap-1" onClick={() => sortBy(c.sort)}>
                      {c.label}
                      {sort === c.sort && (dir === 'asc' ? <ArrowUp className="size-3" /> : <ArrowDown className="size-3" />)}
                    </button>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {data?.deliveries.map((d) => (
                <tr key={d.id} className="cursor-pointer border-b last:border-0 hover:bg-secondary" onClick={() => setOpen(d)} data-testid="delivery-row">
                  <td className="p-3 whitespace-nowrap">{dateTime(d.at)}</td>
                  <td className="p-3">{d.source}</td>
                  <td className="p-3">{d.person}</td>
                  <td className="p-3">{statusLabel[d.status]}</td>
                  <td className="p-3">{d.tries}</td>
                </tr>
              ))}
              {data && data.deliveries.length === 0 && (
                <tr>
                  <td colSpan={5} className="p-3 text-muted-foreground">
                    No deliveries.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </CardContent>
      </Card>

      {open && <DeliveryDialog delivery={open} onClose={() => setOpen(null)} />}
    </div>
  )
}

function Chip({ label, value, onChange, options }: { label: string; value: string; onChange: (v: string) => void; options: [string, string][] }) {
  return (
    <label className={`inline-flex items-center gap-1 rounded-full border px-3 py-1 text-sm ${value ? 'border-primary bg-secondary' : ''}`}>
      <span className="text-muted-foreground">{label}:</span>
      <select aria-label={label} value={value} onChange={(e) => onChange(e.target.value)} className="bg-transparent font-medium outline-none">
        {options.map(([v, text]) => (
          <option key={v} value={v}>
            {text}
          </option>
        ))}
      </select>
    </label>
  )
}

/** One delivery, with the event that caused it; a delivery with no event (a heating alert) shows its source instead. */
function DeliveryDialog({ delivery: d, onClose }: { delivery: Delivery; onClose: () => void }) {
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{d.title}</DialogTitle>
          <DialogDescription>{d.body}</DialogDescription>
        </DialogHeader>
        <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
          <dt className="text-muted-foreground">For</dt>
          <dd>{d.person}</dd>
          <dt className="text-muted-foreground">Status</dt>
          <dd>
            {statusLabel[d.status]} after {d.tries} {d.tries === 1 ? 'try' : 'tries'}
          </dd>
          {d.sentAt && (
            <>
              <dt className="text-muted-foreground">Sent</dt>
              <dd>
                {dateTime(d.sentAt)}
                {d.sentTo.length > 0 && ` to ${d.sentTo.join(', ')}`}
              </dd>
            </>
          )}
        </dl>
        {d.attempts.length > 0 && (
          <ul className="text-xs text-destructive">
            {d.attempts.map((a, i) => (
              <li key={i}>
                {dateTime(a.at)}: {a.error}
              </li>
            ))}
          </ul>
        )}
        <div data-testid="delivery-cause" className="rounded-lg bg-secondary p-3 text-sm">
          {d.event ? (
            <>
              <div className="font-medium">Event{d.event.topic ? ` from ${d.event.topic}` : ''}</div>
              {d.event.at && <div className="text-xs text-muted-foreground">{dateTime(d.event.at)}</div>}
              {d.event.payload != null && <pre className="mt-1 overflow-x-auto text-xs">{JSON.stringify(d.event.payload, null, 2)}</pre>}
            </>
          ) : (
            <>
              <div className="font-medium">Source</div>
              <div>{d.source}</div>
            </>
          )}
        </div>
      </DialogContent>
    </Dialog>
  )
}
