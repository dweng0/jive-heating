import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { Lightbulb, Plus, Trash2 } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Switch } from '@/components/ui/switch'
import { DeleteRuleDialog } from '@/components/heating/DeleteRuleDialog'
import { SuggestedRules } from '@/components/heating/SuggestedRules'
import { areaAlertApi, hoursText, type AreaAlert } from '@/lib/areaAlerts'
import { energyApi } from '@/lib/energy'
import { ago } from '@/lib/events'
import { useHome } from '@/lib/home'
import { asRequest, notSentText, rulesApi, type Rule } from '@/lib/rules'

interface AreaRow {
  id: string
  name: string
  alert: AreaAlert
}

type ChipKey = 'on' | 'off' | 'paused' | 'me' | 'everyone'

const CHIPS: { key: ChipKey; label: string }[] = [
  { key: 'on', label: 'On' },
  { key: 'off', label: 'Off' },
  { key: 'paused', label: 'Paused' },
  { key: 'me', label: 'Notifies me' },
  { key: 'everyone', label: 'Notifies everyone' },
]

const isPaused = (r: Rule) => !!r.pausedUntil && new Date(r.pausedUntil).getTime() > Date.now()

/** Notifications: every rule and area notification, with an on/off switch, filter chips, and a way to open one to edit, test, see its history or delete it. */
export function RulesPage() {
  const { me } = useHome()
  const [rules, setRules] = useState<Rule[] | null>(null)
  const [areas, setAreas] = useState<AreaRow[]>([])
  const [error, setError] = useState<string | null>(null)
  const [chips, setChips] = useState<Set<ChipKey>>(new Set())
  const [topic, setTopic] = useState('')
  const [deleting, setDeleting] = useState<Rule | null>(null)
  const [suggesting, setSuggesting] = useState(false)

  useEffect(() => {
    let cancelled = false
    rulesApi.list().then(
      (r) => !cancelled && setRules(r),
      (e: Error) => !cancelled && setError(e.message),
    )
    energyApi
      .areas()
      .then((list) => Promise.all(list.map((a) => areaAlertApi.get(a.id).then((alert) => ({ id: a.id, name: a.name, alert })))))
      .then(
        (rows) => !cancelled && setAreas(rows.filter((r) => r.alert.hours != null)),
        () => undefined,
      )
    return () => {
      cancelled = true
    }
  }, [])

  const topics = useMemo(() => [...new Set((rules ?? []).map((r) => r.topic))].sort(), [rules])
  const notifiesMe = (r: Rule) => r.notify === 'me' && r.byUserId === me.id

  const shown = (rules ?? []).filter((r) => {
    if (chips.has('on') && !r.enabled) return false
    if (chips.has('off') && r.enabled) return false
    if (chips.has('paused') && !isPaused(r)) return false
    if (chips.has('me') && !notifiesMe(r)) return false
    if (chips.has('everyone') && r.notify !== 'everyone') return false
    if (topic && r.topic !== topic) return false
    return true
  })
  // Area notifications are always on and have no topic.
  const shownAreas = areas.filter((a) => {
    if (chips.has('off') || chips.has('paused') || topic) return false
    if (chips.has('me') && (a.alert.notify !== 'me' || !a.alert.mine)) return false
    if (chips.has('everyone') && a.alert.notify !== 'everyone') return false
    return true
  })

  function toggle(key: ChipKey) {
    setChips((old) => {
      const next = new Set(old)
      if (!next.delete(key)) {
        next.add(key)
        // On and Off, and the two audiences, exclude each other.
        const opposite: Partial<Record<ChipKey, ChipKey>> = { on: 'off', off: 'on', me: 'everyone', everyone: 'me' }
        const other = opposite[key]
        if (other) next.delete(other)
      }
      return next
    })
  }

  const replace = (r: Rule) => setRules((old) => (old ?? []).map((x) => (x.id === r.id ? { ...r, lastSentAt: x.lastSentAt } : x)))

  async function setEnabled(rule: Rule, enabled: boolean) {
    try {
      replace(await rulesApi.replace(rule.id, { ...asRequest(rule), enabled }))
    } catch (e) {
      toast.error((e as Error).message)
    }
  }

  async function resume(rule: Rule) {
    try {
      replace(await rulesApi.resume(rule.id))
    } catch (e) {
      toast.error((e as Error).message)
    }
  }

  async function remove(rule: Rule) {
    setDeleting(null)
    try {
      await rulesApi.remove(rule.id)
      setRules((old) => (old ?? []).filter((x) => x.id !== rule.id))
      toast.success(`${rule.name} deleted`)
    } catch (e) {
      toast.error((e as Error).message)
    }
  }

  const who = (r: Rule) => (r.notify === 'everyone' ? 'Everyone' : r.byUserId === me.id ? 'Me' : r.by)

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-2xl font-semibold">Notifications</h1>
        <div className="ml-auto flex gap-2">
          <Button variant="outline" size="sm" aria-pressed={suggesting} onClick={() => setSuggesting(!suggesting)}>
            <Lightbulb /> Suggested
          </Button>
          <Button size="sm" asChild>
            <Link to="/rules/new">
              <Plus /> New rule
            </Link>
          </Button>
          <Button variant="ghost" size="sm" asChild>
            <Link to="/account/deliveries">Deliveries</Link>
          </Button>
        </div>
      </div>

      {suggesting && <SuggestedRules />}

      <div className="flex flex-wrap items-center gap-2" data-testid="rule-chips">
        {CHIPS.map((c) => (
          <button
            key={c.key}
            type="button"
            aria-pressed={chips.has(c.key)}
            onClick={() => toggle(c.key)}
            className={`rounded-full border px-3 py-1 text-sm ${chips.has(c.key) ? 'border-primary bg-secondary font-medium' : ''}`}
          >
            {c.label}
          </button>
        ))}
        <span className={`inline-flex items-center gap-1 rounded-full border px-3 py-1 text-sm ${topic ? 'border-primary bg-secondary' : ''}`}>
          <span className="text-muted-foreground">Topic:</span>
          <select aria-label="Topic" value={topic} onChange={(e) => setTopic(e.target.value)} className="bg-transparent font-medium outline-none">
            <option value="">Any topic</option>
            {topics.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
        </span>
      </div>

      {error && <p className="text-sm text-destructive">{error}</p>}
      <Card>
        <CardContent className="p-0">
          <ul className="divide-y text-sm">
            {shown.map((r) => (
              <li key={r.id} data-testid="rule-row" className="flex flex-wrap items-center gap-x-4 gap-y-1 p-3">
                <div className="min-w-0 flex-1">
                  <Link to={`/rules/${r.id}`} className="font-medium hover:underline">
                    {r.name}
                  </Link>
                  <div className="font-mono text-xs break-all text-muted-foreground">{r.topic}</div>
                </div>
                <div className="text-xs text-muted-foreground">
                  <div>{who(r)}</div>
                  <div>{r.lastSentAt ? ago(r.lastSentAt) : notSentText(r.createdAt)}</div>
                </div>
                {isPaused(r) && (
                  <div className="flex items-center gap-2 text-xs">
                    <span>Paused until {r.pausedUntilLocal}</span>
                    <Button variant="outline" size="xs" onClick={() => resume(r)}>
                      Resume
                    </Button>
                  </div>
                )}
                <Switch checked={r.enabled} onCheckedChange={(on) => setEnabled(r, on)} aria-label={`${r.name} on`} />
                <Button variant="ghost" size="icon-sm" aria-label="Delete" onClick={() => setDeleting(r)}>
                  <Trash2 />
                </Button>
              </li>
            ))}
            {shownAreas.map((a) => (
              <li key={a.id} data-testid="area-row" className="flex flex-wrap items-center gap-x-4 gap-y-1 p-3">
                <div className="min-w-0 flex-1">
                  <Link to={`/energy/areas/${a.id}`} className="font-medium hover:underline">
                    {a.name}
                  </Link>
                  <div className="text-xs text-muted-foreground">Area notification, after {hoursText(a.alert.hours ?? 0)}</div>
                </div>
                <div className="text-xs text-muted-foreground">{a.alert.notify === 'everyone' ? 'Everyone' : a.alert.mine ? 'Me' : a.alert.by}</div>
              </li>
            ))}
            {rules && shown.length === 0 && shownAreas.length === 0 && <li className="p-3 text-muted-foreground">No rules.</li>}
          </ul>
        </CardContent>
      </Card>

      <DeleteRuleDialog name={deleting?.name ?? null} onCancel={() => setDeleting(null)} onConfirm={() => deleting && remove(deleting)} />
    </div>
  )
}
