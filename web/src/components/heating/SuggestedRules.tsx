import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Check } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { eventsApi, z2mBase } from '@/lib/events'
import { alreadySetUp, builderAddress, conditionText, rulesApi, suggestionsFor, type Rule, type Suggestion } from '@/lib/rules'

/** What a suggestion reads as: "contact changes to false", "battery < 15". */
function describe(s: Suggestion): string {
  const c = s.conditions[0]
  return c.operator === '=' && s.trigger === 'changes to' ? `${c.field} changes to ${String(c.value)}` : conditionText(c)
}

/**
 * Rules worth having for Zigbee2MQTT devices, from what each exposes on its bridge/devices entry: contact, water_leak, battery, occupancy, tamper and action.
 * With [device] only that device's; devices that already have a rule like it show "Set up" instead of "Add".
 */
export function SuggestedRules({ device }: { device?: string }) {
  const navigate = useNavigate()
  const [suggestions, setSuggestions] = useState<Suggestion[] | null>(null)
  const [rules, setRules] = useState<Rule[]>([])

  useEffect(() => {
    let cancelled = false
    Promise.all([eventsApi.topics(), rulesApi.list()]).then(
      ([topics, existing]) => {
        if (cancelled) return
        const bridge = topics.find((t) => t.topic.endsWith('/bridge/devices'))
        setSuggestions(bridge ? suggestionsFor(bridge.lastPayload, z2mBase(topics)) : [])
        setRules(existing)
      },
      () => !cancelled && setSuggestions([]),
    )
    return () => {
      cancelled = true
    }
  }, [])

  const shown = (suggestions ?? []).filter((s) => !device || s.device === device)
  if (shown.length === 0) {
    return suggestions && !device ? <p className="text-sm text-muted-foreground">No Zigbee2MQTT devices to suggest rules for.</p> : null
  }
  return (
    <Card>
      <CardContent className="flex flex-col gap-2 pt-6">
        <h2 className="font-semibold">{device ? `Suggested rules for ${device}` : 'Suggested rules'}</h2>
        <ul className="divide-y text-sm" data-testid="suggestions">
          {shown.map((s) => {
            const done = alreadySetUp(s, rules)
            return (
              <li key={`${s.topic}/${s.name}`} data-testid="suggestion" className="flex flex-wrap items-center gap-x-3 gap-y-1 py-2">
                <span className="font-medium">{s.name}</span>
                <span className="text-xs text-muted-foreground">{describe(s)}</span>
                <span className="ml-auto">
                  {done ? (
                    <span className="inline-flex items-center gap-1 text-xs text-muted-foreground">
                      <Check className="size-3.5" /> Set up
                    </span>
                  ) : (
                    <Button variant="outline" size="xs" aria-label={`Add ${s.name}`} onClick={() => navigate(builderAddress(s))}>
                      Add
                    </Button>
                  )}
                </span>
              </li>
            )
          })}
        </ul>
      </CardContent>
    </Card>
  )
}
