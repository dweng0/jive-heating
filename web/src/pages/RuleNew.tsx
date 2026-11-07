import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { ChevronLeft, Plus, X } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { eventsApi, type LoggedEvent } from '@/lib/events'
import {
  asRequest,
  conditionFromAddress,
  defaultTexts,
  emptyRequest,
  kindOf,
  OPERATORS,
  operatorLabel,
  parseValue,
  rulesApi,
  seenFields,
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

  // The events seen on the topic, for the fields it offers.
  const topic = req?.topic ?? ''
  useEffect(() => {
    if (!topic.trim()) return
    let cancelled = false
    const t = setTimeout(() => {
      eventsApi.list(new URLSearchParams({ topic: topic.trim(), sort: 'at desc', limit: '50' }).toString()).then(
        (p) => !cancelled && setSeen(p.events),
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

  async function save() {
    if (!req) return
    setSaving(true)
    setError(null)
    try {
      if (id) await rulesApi.replace(id, req)
      else await rulesApi.create(req)
      toast.success(id ? 'Rule saved' : 'Rule added')
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
            <Input aria-label="Topic" className="font-mono" value={req.topic} onChange={(e) => change({ topic: e.target.value })} />
          </Labelled>

          <div className="flex flex-col gap-2">
            <span className="text-sm font-medium">Conditions</span>
            {req.conditions.map((c, i) => (
              <ConditionRow
                key={i}
                condition={c}
                fieldKind={fields.find((f) => f.path === c.field)?.kind}
                onChange={(next) => change({ conditions: req.conditions.map((x, j) => (j === i ? next : x)) })}
                onRemove={() => change({ conditions: req.conditions.filter((_, j) => j !== i) })}
              />
            ))}
            <div>
              <Button variant="outline" size="sm" onClick={() => change({ conditions: [...req.conditions, { field: fields[0]?.path ?? '', operator: 'exists' }] })}>
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
            <Button onClick={save} disabled={saving}>
              {id ? 'Save' : 'Add rule'}
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

function ConditionRow({ condition: c, fieldKind, onChange, onRemove }: {
  condition: Condition
  fieldKind?: FieldKind
  onChange: (c: Condition) => void
  onRemove: () => void
}) {
  const kind = fieldKind ?? (c.value === undefined ? 'text' : kindOf(c.value))
  const choices: string[] = kind === 'boolean' ? ['is true', 'is false', 'exists'] : OPERATORS[kind]
  function pick(choice: string) {
    if (choice === 'is true' || choice === 'is false') onChange({ field: c.field, operator: '=', value: choice === 'is true' })
    else if (choice === 'exists') onChange({ field: c.field, operator: 'exists' })
    else onChange({ field: c.field, operator: choice as Operator, value: c.value ?? '' })
  }
  return (
    <div data-testid="condition" className="flex flex-wrap items-center gap-2">
      <Input aria-label="Field" className="w-40 font-mono" value={c.field} onChange={(e) => onChange({ ...c, field: e.target.value })} />
      <select aria-label="Operator" className={selectCls} value={operatorChoice(kind, c)} onChange={(e) => pick(e.target.value)}>
        {choices.map((o) => (
          <option key={o} value={o}>
            {o}
          </option>
        ))}
      </select>
      {c.operator !== 'exists' && kind !== 'boolean' && (
        <Input aria-label="Value" className="w-40" value={c.value === undefined ? '' : String(c.value)} onChange={(e) => onChange({ ...c, value: parseValue(kind, e.target.value) })} />
      )}
      <Button variant="ghost" size="icon-sm" aria-label="Remove condition" onClick={onRemove}>
        <X />
      </Button>
    </div>
  )
}
