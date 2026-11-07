import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { ArrowDown, ArrowUp, ChevronDown, ChevronRight, Pause, Play, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { SuggestedRules } from '@/components/heating/SuggestedRules'
import {
  ago,
  eventQuery,
  eventsApi,
  filterablePath,
  filterFromParams,
  filterToParams,
  parseFieldValue,
  payloadFields,
  RANGES,
  shortPayload,
  STALE_DAYS,
  topicTree,
  valueText,
  z2mBase,
  type EventFilter,
  type EventSort,
  type EventTopic,
  type LoggedEvent,
  type TopicNode,
} from '@/lib/events'

/** How often the newest events are looked for. */
const LIVE_MILLIS = 2000
/** Scrolled further down than this, new events wait instead of jumping in. */
const SCROLLED_PX = 100
const CHIP_KEYS = ['topic', 'device', 'field', 'fv', 'text', 'range', 'retained']

const eventTime = (iso: string) =>
  new Date(iso).toLocaleString([], { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' })

type ChipKind = keyof EventFilter

interface ChipView {
  kind: ChipKind
  label: string
}

function chipsOf(f: EventFilter): ChipView[] {
  const chips: ChipView[] = []
  if (f.topic) chips.push({ kind: 'topic', label: `Topic: ${f.topic}` })
  if (f.device) chips.push({ kind: 'device', label: `Device: ${f.device}` })
  if (f.field) chips.push({ kind: 'field', label: `Field: ${f.field}` })
  if (f.fieldValue) chips.push({ kind: 'fieldValue', label: `${f.fieldValue.field} ${f.fieldValue.not ? '≠' : '='} ${f.fieldValue.value}` })
  if (f.text) chips.push({ kind: 'text', label: `Text: ${f.text}` })
  if (f.range) chips.push({ kind: 'range', label: RANGES.find((r) => r.value === f.range)?.label ?? f.range })
  if (f.retained !== undefined) chips.push({ kind: 'retained', label: f.retained ? 'Retained' : 'Not retained' })
  return chips
}

/** A filter with [add] in it: one chip of each kind, and Field and Field value replace each other. */
function withChip(f: EventFilter, add: EventFilter): EventFilter {
  const next = { ...f, ...add }
  if (add.field) delete next.fieldValue
  if (add.fieldValue) delete next.field
  return next
}

interface Suggestion {
  label: string
  add: EventFilter
}

function suggest(text: string, topics: EventTopic[]): Suggestion[] {
  const out: Suggestion[] = []
  const q = text.trim()
  const lower = q.toLowerCase()
  if (!q) {
    RANGES.forEach((r) => out.push({ label: r.label, add: { range: r.value } }))
    out.push({ label: 'Retained', add: { retained: true } }, { label: 'Not retained', add: { retained: false } })
    return out
  }
  const fv = parseFieldValue(q)
  if (fv) out.push({ label: `${fv.field} ${fv.not ? '≠' : '='} ${fv.value}`, add: { fieldValue: fv } })
  if (/[+#/]/.test(q)) out.push({ label: `Topic: ${q}`, add: { topic: q } })
  topics
    .filter((t) => t.topic.toLowerCase().includes(lower))
    .slice(0, 5)
    .forEach((t) => out.push({ label: `Topic: ${t.topic}`, add: { topic: t.topic } }))
  const base = z2mBase(topics)
  const devices = new Set<string>()
  topics.forEach((t) => {
    const m = t.topic.match(new RegExp(`^${base.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}/([^/]+)$`))
    if (m && m[1] !== 'bridge') devices.add(m[1])
  })
  ;[...devices].filter((d) => d.toLowerCase().includes(lower)).slice(0, 5).forEach((d) => out.push({ label: `Device: ${d}`, add: { device: d } }))
  const fields = new Set<string>()
  topics.forEach((t) => t.json && payloadFields(t.lastPayload).forEach((f) => filterablePath(f.path) && fields.add(f.path)))
  ;[...fields].filter((f) => f.toLowerCase().includes(lower)).slice(0, 5).forEach((f) => out.push({ label: `Field: ${f}`, add: { field: f } }))
  RANGES.filter((r) => r.label.toLowerCase().includes(lower)).forEach((r) => out.push({ label: r.label, add: { range: r.value } }))
  if ('retained'.includes(lower)) out.push({ label: 'Retained', add: { retained: true } })
  if ('not retained'.includes(lower)) out.push({ label: 'Not retained', add: { retained: false } })
  out.push({ label: `Text: ${q}`, add: { text: q } })
  return out
}

/** Events (admins): a searchable table of every MQTT message, filtered with chips kept in the page address. */
export function EventsPage() {
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  const filter = useMemo(() => filterFromParams(params), [params])
  const sort: EventSort = params.get('sort') === 'topic' ? 'topic' : 'at'
  const descending = params.get('dir') !== 'asc'
  const view = params.get('view') === 'topics' ? 'topics' : 'events'
  const chips = chipsOf(filter)
  // What the table shows changes only with these.
  const key = `${filterToParams(filter).toString()}|${sort}|${descending}`

  const [events, setEvents] = useState<LoggedEvent[]>([])
  const [next, setNext] = useState<string | null>(null)
  const [total, setTotal] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [paused, setPaused] = useState(false)
  const [open, setOpen] = useState<LoggedEvent | null>(null)
  const [topics, setTopics] = useState<EventTopic[]>([])
  const generation = useRef(0)
  const loadingMore = useRef(false)
  const newest = useRef(0)

  const update = useCallback(
    (change: (p: URLSearchParams) => void) => {
      const p = new URLSearchParams(params)
      change(p)
      setParams(p)
    },
    [params, setParams],
  )

  const setFilter = useCallback(
    (f: EventFilter) =>
      update((p) => {
        CHIP_KEYS.forEach((k) => p.delete(k))
        filterToParams(f, p)
        p.delete('view')
      }),
    [update],
  )

  useEffect(() => {
    eventsApi.topics().then(setTopics, () => undefined)
  }, [view])

  // The first page whenever the filter or sort changes.
  useEffect(() => {
    const mine = ++generation.current
    const [f, s, d] = [filter, sort, descending]
    eventsApi.list(eventQuery(f, s, d)).then(
      (page) => {
        if (mine !== generation.current) return
        setEvents(page.events)
        setNext(page.next)
        setTotal(page.total)
        setError(null)
        newest.current = page.events.reduce((m, e) => Math.max(m, e.id), 0)
      },
      (e: Error) => mine === generation.current && setError(e.message),
    )
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])

  const loadMore = useCallback(() => {
    if (!next || loadingMore.current) return
    const mine = generation.current
    loadingMore.current = true
    eventsApi
      .list(eventQuery(filter, sort, descending, next))
      .then(
        (page) => {
          if (mine !== generation.current) return
          setEvents((old) => [...old, ...page.events.filter((e) => !old.some((o) => o.id === e.id))])
          setNext(page.next)
          setTotal(page.total)
        },
        (e: Error) => setError(e.message),
      )
      .finally(() => {
        loadingMore.current = false
      })
  }, [next, filter, sort, descending])

  // Live: new events join the top, unless paused, scrolled down, or sorted some other way.
  const live = view === 'events' && sort === 'at' && descending
  useEffect(() => {
    if (!live || paused) return
    const timer = window.setInterval(() => {
      if (window.scrollY > SCROLLED_PX) return
      const mine = generation.current
      eventsApi.list(eventQuery(filter, sort, descending)).then(
        (page) => {
          if (mine !== generation.current || window.scrollY > SCROLLED_PX) return
          const fresh = page.events.filter((e) => e.id > newest.current)
          if (fresh.length === 0) return setTotal(page.total)
          newest.current = Math.max(newest.current, ...fresh.map((e) => e.id))
          setTotal(page.total)
          if (fresh.length === page.events.length && page.next) {
            // A whole page of new events: there may be more between, so start again from this one.
            setEvents(page.events)
            setNext(page.next)
          } else {
            setEvents((old) => [...fresh, ...old])
          }
        },
        () => undefined,
      )
    }, LIVE_MILLIS)
    return () => window.clearInterval(timer)
  }, [live, paused, filter, sort, descending])

  // The next 100 load as the end of the table comes into view.
  const sentinel = useRef<HTMLDivElement | null>(null)
  useEffect(() => {
    const el = sentinel.current
    if (!el || !next) return
    const observer = new IntersectionObserver((entries) => entries.some((e) => e.isIntersecting) && loadMore(), { rootMargin: '200px' })
    observer.observe(el)
    return () => observer.disconnect()
  }, [next, events.length, loadMore, view])

  function sortBy(column: EventSort) {
    update((p) => {
      const nextDescending = column === sort ? !descending : column === 'at'
      if (column === 'at' && nextDescending) p.delete('sort')
      else p.set('sort', column)
      if (nextDescending) p.delete('dir')
      else p.set('dir', 'asc')
    })
  }

  const columns: { sort?: EventSort; label: string }[] = [{ sort: 'at', label: 'Time' }, { sort: 'topic', label: 'Topic' }, { label: 'Payload' }]

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-2xl font-semibold">Events</h1>
        <div role="tablist" className="inline-flex rounded-lg bg-muted p-[3px] text-sm">
          {(['events', 'topics'] as const).map((v) => (
            <button
              key={v}
              role="tab"
              type="button"
              aria-selected={view === v}
              className={`rounded-md px-3 py-1 font-medium ${view === v ? 'bg-background shadow-sm' : 'text-muted-foreground'}`}
              onClick={() => update((p) => (v === 'topics' ? p.set('view', 'topics') : p.delete('view')))}
            >
              {v === 'events' ? 'Events' : 'Topics'}
            </button>
          ))}
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-2" data-testid="event-chips">
        {chips.map((c) => (
          <span key={c.kind} data-testid="event-chip" className="inline-flex items-center gap-1 rounded-full border border-primary bg-secondary py-1 pr-1 pl-3 text-sm">
            {c.label}
            <button
              type="button"
              aria-label={`Remove ${c.label}`}
              className="rounded-full p-0.5 hover:bg-background"
              onClick={() => setFilter(Object.fromEntries(Object.entries(filter).filter(([k]) => k !== c.kind)) as EventFilter)}
            >
              <X className="size-3.5" />
            </button>
          </span>
        ))}
        <SearchBox topics={topics} onPick={(add) => setFilter(withChip(filter, add))} />
        {chips.length > 0 && (
          <Button variant="ghost" size="sm" onClick={() => setFilter({})}>
            Clear
          </Button>
        )}
      </div>

      {filter.device && <SuggestedRules device={filter.device} />}

      {error && <p className="text-sm text-destructive">{error}</p>}

      {view === 'topics' ? (
        <TopicTree topics={topics} onShow={(topic) => setFilter(withChip(filter, { topic }))} />
      ) : (
        <>
          <div className="flex items-center gap-3 text-sm text-muted-foreground">
            <span data-testid="event-count">{total == null ? '' : `${total.toLocaleString()} event${total === 1 ? '' : 's'}`}</span>
            {live && (
              <Button variant="outline" size="sm" className="ml-auto" onClick={() => setPaused(!paused)}>
                {paused ? <Play /> : <Pause />}
                {paused ? 'Resume' : 'Pause'}
              </Button>
            )}
          </div>
          <Card>
            <CardContent className="overflow-x-auto p-0">
              <table className="w-full text-sm" data-testid="events-table">
                <thead>
                  <tr className="border-b text-left text-muted-foreground">
                    {columns.map((c) => (
                      <th key={c.label} className="p-3 font-medium" aria-sort={c.sort && sort === c.sort ? (descending ? 'descending' : 'ascending') : c.sort ? 'none' : undefined}>
                        {c.sort ? (
                          <button type="button" className="inline-flex items-center gap-1" onClick={() => sortBy(c.sort!)}>
                            {c.label}
                            {sort === c.sort && (descending ? <ArrowDown className="size-3" /> : <ArrowUp className="size-3" />)}
                          </button>
                        ) : (
                          c.label
                        )}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {events.map((e) => (
                    <tr key={e.id} className="cursor-pointer border-b last:border-0 hover:bg-secondary" onClick={() => setOpen(e)} data-testid="event-row">
                      <td className="p-3 whitespace-nowrap">{eventTime(e.at)}</td>
                      <td className="p-3 font-mono text-xs break-all">{e.topic}</td>
                      <td className="p-3 font-mono text-xs break-all">
                        {shortPayload(e.payload)}
                        {e.retained && <span className="ml-2 rounded bg-muted px-1.5 py-0.5 font-sans text-[11px] text-muted-foreground">retained</span>}
                      </td>
                    </tr>
                  ))}
                  {total === 0 && (
                    <tr>
                      <td colSpan={3} className="p-3 text-muted-foreground">
                        No events.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </CardContent>
          </Card>
          {next && <div ref={sentinel} className="h-8" data-testid="events-more" />}
        </>
      )}

      {open && (
        <PayloadDialog
          event={open}
          onClose={() => setOpen(null)}
          onFilter={(field, value) => {
            setOpen(null)
            setFilter(withChip(filter, { fieldValue: { field, value } }))
          }}
          onTopic={(topic) => {
            setOpen(null)
            setFilter(withChip(filter, { topic }))
          }}
          onNotify={(topic, field, value) => {
            const p = new URLSearchParams({ topic })
            if (field) p.set('field', field)
            if (value !== undefined) p.set('value', value)
            navigate(`/rules/new?${p}`)
          }}
        />
      )}
    </div>
  )
}

/** The text box that offers matching topics, devices, fields and filters as chips to add. */
function SearchBox({ topics, onPick }: { topics: EventTopic[]; onPick: (add: EventFilter) => void }) {
  const [text, setText] = useState('')
  const [focused, setFocused] = useState(false)
  const [active, setActive] = useState(0)
  const suggestions = useMemo(() => suggest(text, topics), [text, topics])
  const shown = focused && suggestions.length > 0

  function pick(s: Suggestion) {
    onPick(s.add)
    setText('')
    setActive(0)
  }

  return (
    <div className="relative min-w-48 flex-1">
      <input
        role="combobox"
        aria-label="Search events"
        aria-expanded={shown}
        aria-controls="event-suggestions"
        aria-autocomplete="list"
        placeholder="Search topics, devices, fields…"
        value={text}
        className="h-9 w-full rounded-lg border bg-background px-3 text-sm outline-none focus-visible:ring-2 focus-visible:ring-ring"
        onChange={(e) => {
          setText(e.target.value)
          setActive(0)
        }}
        onFocus={() => setFocused(true)}
        onClick={() => setFocused(true)}
        onBlur={() => window.setTimeout(() => setFocused(false), 150)}
        onKeyDown={(e) => {
          if (e.key === 'ArrowDown') {
            e.preventDefault()
            setActive((a) => Math.min(a + 1, suggestions.length - 1))
          } else if (e.key === 'ArrowUp') {
            e.preventDefault()
            setActive((a) => Math.max(a - 1, 0))
          } else if (e.key === 'Enter' && shown && suggestions[active]) {
            e.preventDefault()
            pick(suggestions[active])
          } else if (e.key === 'Escape') setFocused(false)
        }}
      />
      {shown && (
        <ul id="event-suggestions" role="listbox" className="absolute z-20 mt-1 max-h-72 w-full overflow-auto rounded-lg border bg-popover p-1 text-sm shadow-md">
          {suggestions.map((s, i) => (
            <li
              key={s.label}
              role="option"
              aria-selected={i === active}
              className={`cursor-pointer rounded-md px-3 py-1.5 ${i === active ? 'bg-secondary' : ''}`}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => pick(s)}
            >
              {s.label}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

/** One event's whole payload, field by field. */
function PayloadDialog({
  event: e,
  onClose,
  onFilter,
  onTopic,
  onNotify,
}: {
  event: LoggedEvent
  onClose: () => void
  onFilter: (field: string, value: string) => void
  onTopic: (topic: string) => void
  onNotify: (topic: string, field?: string, value?: string) => void
}) {
  const [copied, setCopied] = useState(false)
  const text = e.json ? JSON.stringify(e.payload, null, 2) : String(e.payload)
  const fields = e.json ? payloadFields(e.payload) : []
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle className="font-mono text-base break-all">{e.topic}</DialogTitle>
          <DialogDescription>
            {eventTime(e.at)}
            {e.retained && ' · retained'}
            {e.truncated && ' · cut short'}
          </DialogDescription>
        </DialogHeader>
        <pre data-testid="payload-json" className="max-h-64 overflow-auto rounded-lg bg-secondary p-3 text-xs">
          {text}
        </pre>
        {fields.length > 0 && (
          <ul className="max-h-60 divide-y overflow-auto text-sm">
            {fields.map((f) => (
              <li key={f.path} data-testid="payload-field" className="flex flex-wrap items-center gap-x-3 gap-y-1 py-1.5">
                <span className="font-mono text-xs font-medium">{f.path}</span>
                <span className="font-mono text-xs text-muted-foreground break-all">{shortPayload(f.value, 60)}</span>
                <span className="ml-auto flex gap-1">
                  {filterablePath(f.path) && (
                    <Button variant="outline" size="xs" onClick={() => onFilter(f.path, valueText(f.value))}>
                      Filter by this
                    </Button>
                  )}
                  <Button variant="outline" size="xs" onClick={() => onNotify(e.topic, f.path, valueText(f.value))}>
                    Notify me when…
                  </Button>
                </span>
              </li>
            ))}
          </ul>
        )}
        <div className="flex flex-wrap gap-2">
          <Button variant="outline" onClick={() => onTopic(e.topic)}>
            Show this topic
          </Button>
          <Button variant="outline" onClick={() => onNotify(e.topic)}>
            Notify me when…
          </Button>
          <Button
            variant="outline"
            onClick={() => {
              navigator.clipboard?.writeText(text).then(() => setCopied(true), () => undefined)
            }}
          >
            {copied ? 'Copied' : 'Copy'}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  )
}

/** Topics split on "/" into a tree; each level shows how many messages it holds, when the last came and (for a topic) what it said. */
function TopicTree({ topics, onShow }: { topics: EventTopic[]; onShow: (topic: string) => void }) {
  const tree = useMemo(() => topicTree(topics), [topics])
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const now = Date.now()

  const rows: { node: TopicNode; depth: number }[] = []
  const walk = (nodes: TopicNode[], depth: number) =>
    nodes.forEach((n) => {
      rows.push({ node: n, depth })
      if (expanded.has(n.path)) walk(n.children, depth + 1)
    })
  walk(tree, 0)

  const toggle = (path: string) =>
    setExpanded((old) => {
      const next = new Set(old)
      if (!next.delete(path)) next.add(path)
      return next
    })

  return (
    <Card>
      <CardContent className="p-0" data-testid="topic-tree">
        {rows.length === 0 && <p className="p-3 text-sm text-muted-foreground">No topics yet.</p>}
        {rows.map(({ node, depth }) => {
          const stale = now - new Date(node.lastSeen).getTime() > STALE_DAYS * 86_400_000
          const branch = node.children.length > 0
          return (
            <div
              key={node.path}
              data-testid="topic-node"
              data-stale={stale ? 'true' : 'false'}
              className={`flex flex-wrap items-center gap-x-3 gap-y-1 border-b p-2 text-sm last:border-0 ${stale ? 'text-muted-foreground opacity-60' : ''}`}
              style={{ paddingLeft: `${0.5 + depth * 1.25}rem` }}
            >
              {branch ? (
                <button type="button" aria-label={`${expanded.has(node.path) ? 'Collapse' : 'Expand'} ${node.path}`} onClick={() => toggle(node.path)}>
                  {expanded.has(node.path) ? <ChevronDown className="size-4" /> : <ChevronRight className="size-4" />}
                </button>
              ) : (
                <span className="inline-block size-4" />
              )}
              <button type="button" aria-label={`Show ${branch ? `${node.path}/#` : node.path}`} className="font-mono text-xs font-medium hover:underline" onClick={() => onShow(branch ? `${node.path}/#` : node.path)}>
                {node.name}
              </button>
              <span className="text-xs">
                {node.count.toLocaleString()} message{node.count === 1 ? '' : 's'}
              </span>
              <span className="text-xs">{ago(node.lastSeen, now)}</span>
              {node.own && <span className="min-w-0 flex-1 truncate font-mono text-xs">{shortPayload(node.own.lastPayload, 80)}</span>}
            </div>
          )
        })}
      </CardContent>
    </Card>
  )
}
