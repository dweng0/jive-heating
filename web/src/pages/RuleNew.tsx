import { Link, useSearchParams } from 'react-router-dom'
import { ChevronLeft } from 'lucide-react'
import { Card, CardContent } from '@/components/ui/card'

/** Placeholder for the rule builder (Feature: Rule builder): shows what "Notify me when…" on an event passed along. */
export function RuleNewPage() {
  const [params] = useSearchParams()
  const rows = [
    ['Topic', params.get('topic')],
    ['Field', params.get('field')],
    ['Value', params.get('value')],
  ].filter(([, v]) => v)
  return (
    <div className="flex flex-col gap-4">
      <div>
        <Link to="/events" className="inline-flex items-center gap-1 text-sm text-muted-foreground">
          <ChevronLeft className="size-4" /> Events
        </Link>
        <h1 className="text-2xl font-semibold">New rule</h1>
      </div>
      <Card>
        <CardContent className="flex flex-col gap-3 pt-6">
          <p>Rule builder coming soon</p>
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
            {rows.map(([k, v]) => (
              <div key={k} className="contents">
                <dt className="text-muted-foreground">{k}</dt>
                <dd className="font-mono">{v}</dd>
              </div>
            ))}
          </dl>
        </CardContent>
      </Card>
    </div>
  )
}
