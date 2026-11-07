import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { BellRing } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { deliveriesApi, failedText, type Delivery } from '@/lib/deliveries'
import { dateTime } from '@/lib/format'
import { useHome } from '@/lib/home'

/**
 * Admins only: "N failed notifications" with who, what, when and why for each, "Retry", "Retry all" and "Clear",
 * and a link to the Deliveries table.
 */
export function DeliveriesCard() {
  const { run } = useHome()
  const [failed, setFailed] = useState<Delivery[] | null>(null)

  const load = useCallback(() => deliveriesApi.list({ status: 'failed' }).then((r) => setFailed(r.deliveries), () => setFailed(null)), [])
  useEffect(() => {
    void load()
  }, [load])

  if (!failed) return null

  async function act(work: () => Promise<unknown>, message: string) {
    await run(work, message)
    await load()
  }

  return (
    <Card data-testid="deliveries-card">
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <BellRing className="size-5 text-muted-foreground" /> Notification deliveries
        </CardTitle>
        <CardDescription>
          {failed.length === 0 ? 'Every notification has been delivered.' : <span data-testid="failed-count" className="text-destructive">{failedText(failed.length)}</span>}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {failed.map((d) => (
          <div key={d.id} className="flex flex-col gap-2 rounded-lg bg-secondary p-3 text-sm" data-testid="failed-delivery">
            <div>
              <div className="font-medium">{d.title}</div>
              <div className="text-muted-foreground">{d.body}</div>
              <div className="mt-1 text-xs text-muted-foreground">
                For {d.person} · {dateTime(d.at)}
              </div>
              <div className="text-xs text-destructive">
                {d.attempts.map((a) => `${dateTime(a.at)}: ${a.error}`).join(' · ')}
              </div>
            </div>
            <div className="flex gap-2">
              <Button size="sm" variant="outline" onClick={() => act(() => deliveriesApi.retry(d.id), "Couldn't retry the notification")}>
                Retry
              </Button>
              <Button size="sm" variant="ghost" onClick={() => act(() => deliveriesApi.clear(d.id), "Couldn't clear the notification")}>
                Clear
              </Button>
            </div>
          </div>
        ))}
        <div className="flex items-center gap-2">
          {failed.length > 0 && (
            <Button size="sm" onClick={() => act(() => deliveriesApi.retryAll(), "Couldn't retry the notifications")}>
              Retry all
            </Button>
          )}
          <Button asChild size="sm" variant="outline" className="ml-auto">
            <Link to="/account/deliveries">Deliveries</Link>
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}
