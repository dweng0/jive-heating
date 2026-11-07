import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { ChevronLeft, Plus, X } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { eventsApi, valueText, type EventTopic, type LoggedEvent } from '@/lib/events'
import {
  asRequest,
  conditionFromValue,
  conditionFromAddress,
  defaultTexts,
  deviceOf,
  hasField,
  matchingTopics,
  swapDevice,
  widenedTopic,
  emptyRequest,
  kindOf,
  OPERATORS,
  operatorLabel,
  parseValue,
  rulesApi,
  seenFields,
  type SeenField,
  TEST_SHOWN,
  type Condition,
  type FieldKind,
  type Operator,
  type RuleRequest,
  type TestEvent,
} from '@/lib/rules'
import { dateTime } from '@/lib/format'

export const selectCls = 'h-8 rounded-lg border border-input bg-transparent px-2 text-sm'

const TEST_LABEL: Record<string, string> = { would_send: 'Would send', not_met: 'Conditions not met', no_change: 'No change (already met)' }

/** Rule builder (Feature: Rule builder): /rules/new?topic=&field=&value= starts a rule from an event; /rules/:id edits one. */
export function RuleNewPage() {
  const { id } = useParams()
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const [req, setReq] = useState<RuleRequest | null>(null)
  const [seen, setSeen] = useState<LoggedEvent[]>([])
  const [preview, setPreview] = useState<TestEvent[] | null>(null)
  const [previewError, setPreviewError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [allTopics, setAllTopics] = useState<EventTopic[]>([])
  /** The topic the rule started from, before any widening. */
  const [started, setStarted] = useState('')
  /** The devices ticked in "Pick devices from a list"; null while that list is closed. */
  const [chosen, setChosen] = useState<Set<string> | null>(null)
  /** The texts the admin has not touched follow the topic and conditions. */
  const touched = useRef({ name: false, title: false, message: false })

  // Start from the address (a new rule) or the stored rule (an edit).
  useEffect(() => {
    if (id) {
      rulesApi.get(id).then(
        (r) => {
          touched.current = { name: true, title: true, message: true }
          setReq(asRequest(r))
        },
        (e: Error) => setError(e.message),
      )
      return
    }
    const topic = params.get('topic') ?? ''
    setStarted(topic)
    const field = params.get('field')
    const value = params.get('value')
    const conditions = field ? [conditionFromAddress(field, params.get('op'), value)] : []
    const texts = defaultTexts(topic, conditions)
    const given = { name: params.get('name'), title: params.get('title'), message: params.get('message') }
    touched.current = { name: !!given.name, title: !!given.title, message: !!given.message }
    setReq({
      ...emptyRequest(),
      topic,
      conditions,
      trigger: params.get('trigger') === 'is' ? 'is' : 'changes to',
      name: given.name ?? texts.name,
      title: given.title ?? texts.title,
      message: given.message ?? texts.message,
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])

  useEffect(() => {
    eventsApi.topics().then(setAllTopics, () => undefined)
  }, [])

  // The events seen on the topic, for the fields it offers.
  const topic = req?.topic ?? ''
  useEffect(() => {
    if (!topic.trim()) return
    let cancelled = false
    const t = setTimeout(() => {
      eventsApi.list(new URLSearchParams({ topic: topic.trim(), sort: 'at desc', limit: '50' }).toString()).then(
        (p) => {
          if (cancelled) return
          setSeen(p.events)
          // A condition added before the fields arrived takes the first one.
          const first = seenFields(p.events)[0]
          if (first) setReq((r) => (r && r.conditions.some((c) => !c.field) ? { ...r, conditions: r.conditions.map((c) => (c.field ? c : conditionFromValue(first.path, first.values[0]))) } : r))
        },
        () => undefined,
      )
    }, 250)
    return () => {
      cancelled = true
      clearTimeout(t)
    }
  }, [topic])

  // The live preview: what the rule would have done with the latest events.
  const key = useMemo(() => (req ? JSON.stringify(asRequest(req)) : ''), [req])
  useEffect(() => {
    if (!req || !req.topic.trim()) return
    let cancelled = false
    const t = setTimeout(() => {
      rulesApi.test(JSON.parse(key) as RuleRequest).then(
        (r) => !cancelled && (setPreview(r.events), setPreviewError(null)),
        (e: Error) => !cancelled && setPreviewError(e.message),
      )
    }, 300)
    return () => {
      cancelled = true
      clearTimeout(t)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])

  if (!req) {
    return <p className="text-sm text-muted-foreground">{error ?? 'Loading…'}</p>
  }

  /** Changes the rule; texts the admin has not edited are filled in again from the topic and conditions. */
  function change(patch: Partial<RuleRequest>, mark?: 'name' | 'title' | 'message') {
    setReq((r) => {
      if (!r) return r
      if (mark) touched.current[mark] = true
      const next = { ...r, ...patch }
      if (patch.topic !== undefined || patch.conditions !== undefined) {
        const texts = defaultTexts(next.topic, next.conditions)
        if (!touched.current.name) next.name = texts.name
        if (!touched.current.title) next.title = texts.title
        if (!touched.current.message) next.message = texts.message
      }
      return next
    })
  }

  /** One rule per ticked device: its own topic, with the texts that follow the device filled in for it. */
  function perDevice(topic: string): RuleRequest {
    if (!req) throw new Error('no rule')
    const texts = defaultTexts(topic, req.conditions)
    const swap = (text: string) => swapDevice(swapDevice(text, '{{device}}', deviceOf(topic)), deviceOf(started), deviceOf(topic))
    return {
      ...req,
      topic,
      name: touched.current.name ? swap(req.name) : texts.name,
      title: touched.current.title ? swap(req.title) : texts.title,
      message: touched.current.message ? swap(req.message) : texts.message,
    }
  }

  async function save() {
    if (!req) return
    setSaving(true)
    setError(null)
    try {
      if (id) await rulesApi.replace(id, req)
      else if (chosen) for (const topic of chosen) await rulesApi.create(perDevice(topic))
      else await rulesApi.create(req)
      toast.success(id ? 'Rule saved' : chosen ? `${chosen.size} rules added` : 'Rule added')
      navigate('/rules')
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setSaving(false)
    }
  }

  async function sendTest() {
    if (!req) return
    try {
      const r = await rulesApi.sendTest(req)
      toast.success(`Test sent: ${r.title}`)
    } catch (e) {
      toast.error((e as Error).message)
    }
  }

  const fields = seenFields(seen)
  const wildcard = req.topic.split('/').some((p) => p === '+') || req.topic.endsWith('#')
  const widened = widenedTopic(req.topic)
  const matches = wildcard ? matchingTopics(req.topic.trim(), allTopics) : []
  const conditionFields = req.conditions.map((c) => c.field).filter(Boolean)
  const withFields = (t: EventTopic) => conditionFields.length > 0 && conditionFields.every((f) => hasField(t, f))
  const fieldNote = conditionFields.join(', ')

  return (
    <div className="flex flex-col gap-4">
      <div>
        <Link to={id ? '/rules' : '/events'} className="inline-flex items-center gap-1 text-sm text-muted-foreground">
          <ChevronLeft className="size-4" /> {id ? 'Notifications' : 'Events'}
        </Link>
        <h1 className="text-2xl font-semibold">{id ? 'Edit rule' : 'New rule'}</h1>
      </div>

      <Card>
        <CardContent className="flex flex-col gap-4 pt-6">
          <Labelled label="Topic">
            <Input aria-label="Topic" className="font-mono" value={req.topic} onChange={(e) => (setChosen(null), change({ topic: e.target.value }))} />
          </Labelled>

          {!id && (widened || wildcard) && (
            <div className="flex flex-col gap-2 rounded-lg bg-secondary p-3 text-sm">
              {!wildcard && widened && (
                <div>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => {
                      setStarted((s) => s || req.topic)
                      change({ topic: widened })
                    }}
                  >
                    Any device like this
                    <span className="font-normal text-muted-foreground">
                      {' '}
                      {widened}, {matchingTopics(widened, allTopics).length} topics
                    </span>
                  </Button>
                </div>
              )}
              {wildcard && (
                <>
                  <div className="font-medium">
                    {req.topic} matches {matches.length} {matches.length === 1 ? 'topic' : 'topics'}
                  </div>
                  <ul className="flex flex-col gap-0.5">
                    {matches.map((t) => (
                      <li key={t.topic} data-testid="matched-topic" className="flex flex-wrap gap-x-3">
                        <span className="font-mono text-xs">{t.topic}</span>
                        {withFields(t) && <span className="text-xs text-muted-foreground">has {fieldNote}</span>}
                      </li>
                    ))}
                  </ul>
                  {!chosen && !id && (
                    <div>
                      <Button variant="outline" size="sm" onClick={() => setChosen(new Set(matches.filter(withFields).map((t) => t.topic)))}>
                        Pick devices from a list
                      </Button>
                    </div>
                  )}
                  {chosen && (
                    <ul className="flex flex-col gap-1">
                      {matches.map((t) => (
                        <li key={t.topic} data-testid="device-choice">
                          <label className="flex items-center gap-2">
                            <input
                              type="checkbox"
                              checked={chosen.has(t.topic)}
                              onChange={(e) => {
                                const next = new Set(chosen)
                                if (e.target.checked) next.add(t.topic)
                                else next.delete(t.topic)
                                setChosen(next)
                              }}
                            />
                            <span className="font-mono text-xs">{deviceOf(t.topic)}</span>
                          </label>
                        </li>
                      ))}
                    </ul>
                  )}
                </>
              )}
            </div>
          )}

          <div className="flex flex-col gap-2">
            <span className="text-sm font-medium">Conditions</span>
            {req.conditions.map((c, i) => (
              <ConditionRow
                key={i}
                condition={c}
                fields={fields}
                listId={`values-${i}`}
                onChange={(next) => change({ conditions: req.conditions.map((x, j) => (j === i ? next : x)) })}
                onRemove={() => change({ conditions: req.conditions.filter((_, j) => j !== i) })}
              />
            ))}
            <div>
              <Button variant="outline" size="sm" onClick={() => change({ conditions: [...req.conditions, fields[0] ? conditionFromValue(fields[0].path, fields[0].values[0]) : { field: '', operator: 'exists' }] })}>
                <Plus /> Add a condition
              </Button>
            </div>
          </div>

          <Labelled label="Trigger">
            <select aria-label="Trigger" className={selectCls} value={req.trigger} onChange={(e) => change({ trigger: e.target.value as RuleRequest['trigger'] })}>
              <option value="changes to">changes to</option>
              <option value="is">is</option>
            </select>
          </Labelled>

          <Labelled label="Name">
            <Input aria-label="Name" value={req.name} onChange={(e) => change({ name: e.target.value }, 'name')} />
          </Labelled>
          <Labelled label="Title">
            <Input aria-label="Title" value={req.title} onChange={(e) => change({ title: e.target.value }, 'title')} />
          </Labelled>
          <Labelled label="Message">
            <Input aria-label="Message" value={req.message} onChange={(e) => change({ message: e.target.value }, 'message')} />
          </Labelled>
          <Labelled label="Notify">
            <select aria-label="Notify" className={selectCls} value={req.notify} onChange={(e) => change({ notify: e.target.value as RuleRequest['notify'] })}>
              <option value="me">Me</option>
              <option value="everyone">Everyone</option>
            </select>
          </Labelled>

          {error && <p className="text-sm text-destructive">{error}</p>}
          <div className="flex flex-wrap gap-2">
            <Button onClick={save} disabled={saving || (chosen !== null && chosen.size === 0)}>
              {id ? 'Save' : chosen ? `Add ${chosen.size} ${chosen.size === 1 ? 'rule' : 'rules'}` : 'Add rule'}
            </Button>
            <Button variant="outline" onClick={sendTest}>
              Send me a test
            </Button>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardContent className="flex flex-col gap-2 pt-6">
          <h2 className="font-semibold">Test against recent events</h2>
          {previewError && <p className="text-sm text-destructive">{previewError}</p>}
          <ul className="divide-y text-sm" data-testid="test-events">
            {(preview ?? []).slice(0, TEST_SHOWN).map((e) => (
              <li key={e.id} data-testid="test-event" className="flex flex-wrap items-center gap-x-3 gap-y-1 py-2">
                <span className="text-xs text-muted-foreground whitespace-nowrap">{dateTime(e.at)}</span>
                <span className="font-mono text-xs break-all">{e.payload}</span>
                <span className={`ml-auto rounded px-1.5 py-0.5 text-xs ${e.status === 'would_send' ? 'bg-primary/10 font-medium' : 'bg-muted text-muted-foreground'}`}>
                  {TEST_LABEL[e.status] ?? e.status}
                </span>
              </li>
            ))}
            {preview && preview.length === 0 && <li className="py-2 text-muted-foreground">No events on this topic yet.</li>}
          </ul>
        </CardContent>
      </Card>
    </div>
  )
}

function Labelled({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1 text-sm">
      <span className="font-medium">{label}</span>
      {children}
    </label>
  )
}

/** A condition's operator as one list entry: booleans read "is true" and "is false". */
function operatorChoice(kind: FieldKind, c: Condition): string {
  return c.operator === 'exists' ? 'exists' : operatorLabel(kind, c.operator, c.value)
}

function ConditionRow({ condition: c, fields, listId, onChange, onRemove }: {
  condition: Condition
  fields: SeenField[]
  listId: string
  onChange: (c: Condition) => void
  onRemove: () => void
}) {
  const seenField = fields.find((f) => f.path === c.field)
  const kind = seenField?.kind ?? (c.value === undefined ? 'text' : kindOf(c.value))
  const choices: string[] = kind === 'boolean' ? ['is true', 'is false', 'exists'] : OPERATORS[kind]
  function pick(choice: string) {
    if (choice === 'is true' || choice === 'is false') onChange({ field: c.field, operator: '=', value: choice === 'is true' })
    else if (choice === 'exists') onChange({ field: c.field, operator: 'exists' })
    else onChange({ field: c.field, operator: choice as Operator, value: c.value ?? '' })
  }
  const known = fields.some((f) => f.path === c.field)
  return (
    <div data-testid="condition" className="flex flex-wrap items-center gap-2">
      <select
        aria-label="Field"
        className={`${selectCls} max-w-64`}
        value={c.field}
        onChange={(e) => {
          const next = fields.find((f) => f.path === e.target.value)
          onChange(next ? conditionFromValue(next.path, next.values[0]) : { field: e.target.value, operator: 'exists' })
        }}
      >
        {!known && c.field && <option value={c.field}>{c.field}</option>}
        {fields.map((f) => (
          <option key={f.path} value={f.path}>
            {f.path} ({f.values.slice(0, 5).map((v) => shortValue(v)).join(', ')})
          </option>
        ))}
      </select>
      <select aria-label="Operator" className={selectCls} value={operatorChoice(kind, c)} onChange={(e) => pick(e.target.value)}>
        {choices.map((o) => (
          <option key={o} value={o}>
            {o}
          </option>
        ))}
      </select>
      {c.operator !== 'exists' && kind !== 'boolean' && (
        <>
          <Input aria-label="Value" className="w-40" list={listId} value={c.value === undefined ? '' : String(c.value)} onChange={(e) => onChange({ ...c, value: parseValue(kind, e.target.value) })} />
          <datalist id={listId}>
            {(seenField?.values ?? []).slice(0, 10).map((v) => (
              <option key={valueText(v)} value={valueText(v)} />
            ))}
          </datalist>
        </>
      )}
      <Button variant="ghost" size="icon-sm" aria-label="Remove condition" onClick={onRemove}>
        <X />
      </Button>
    </div>
  )
}

const shortValue = (v: unknown) => {
  const t = valueText(v)
  return t.length > 20 ? `${t.slice(0, 20)}…` : t
}
