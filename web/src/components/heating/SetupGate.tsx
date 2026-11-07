import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { Unplug } from 'lucide-react'
import { ConnectionSetup } from '@/components/heating/ConnectionSetup'
import { useHome } from '@/lib/home'
import { lostText, setupApi, type SetupStatus } from '@/lib/setup'

const POLL_MS = 15_000
const CARRY_ON: SetupStatus = { broker: 'connected', zigbee2mqtt: 'configured', complete: true }

/**
 * For admins: a new install opens the connection setup steps instead of the app, and a lost broker shows a banner
 * linking to Settings > Connection. Members never see either (setup is for admins only).
 */
export function SetupGate({ children }: { children: ReactNode }) {
  const { me } = useHome()
  const [status, setStatus] = useState<SetupStatus | null>(null)
  const location = useLocation()

  const refresh = useCallback(() => {
    // If the status can't be read, carry on as if set up rather than hide the app.
    setupApi.status().then(setStatus, () => setStatus((s) => s ?? CARRY_ON))
  }, [])

  useEffect(() => {
    if (!me.admin) return
    refresh()
    const timer = setInterval(refresh, POLL_MS)
    return () => clearInterval(timer)
  }, [me.admin, refresh])

  if (!me.admin) return <>{children}</>
  if (status == null) return null
  if (!status.complete && location.pathname !== '/settings/connection') return <ConnectionSetup onFinish={refresh} />

  return (
    <>
      {status.broker === 'disconnected' && (
        <div data-testid="broker-banner" role="status" className="-mt-8 mb-6 flex items-center gap-3 rounded-xl border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive">
          <Unplug className="size-4 shrink-0" />
          <span className="font-medium">{lostText(status.since)}</span>
          <Link to="/settings/connection" className="ml-auto underline underline-offset-2">
            Settings &gt; Connection
          </Link>
        </div>
      )}
      {children}
    </>
  )
}
