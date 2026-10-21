import { useEffect, useState } from 'react'
import { api, type History, type HistoryRange } from '@/lib/api'

export const RANGES: { value: HistoryRange; label: string }[] = [
  { value: '24h', label: '24 hours' },
  { value: '7d', label: '7 days' },
  { value: '30d', label: '30 days' },
]

/** How often open charts refetch; history is recorded every five minutes. */
const REFRESH_MS = 5 * 60_000

/** History for [range], refetched every five minutes; `failed` once a fetch fails with nothing shown yet. */
export function useHistory(range: HistoryRange) {
  const [history, setHistory] = useState<History | null>(null)
  const [failed, setFailed] = useState(false)
  useEffect(() => {
    let cancelled = false
    const load = () =>
      api.history(range).then(
        (h) => {
          if (cancelled) return
          setHistory(h)
          setFailed(false)
        },
        () => !cancelled && setFailed(true),
      )
    load()
    const timer = window.setInterval(load, REFRESH_MS)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [range])
  return { history, failed }
}
