import { useEffect, useRef, useState } from 'react'
import { LeagueBoard } from '@/components/heating/LeagueTable'
import { LiveDial } from '@/components/heating/LiveDial'
import { followPower, type LivePower } from '@/lib/livePower'
import { startLive, type LiveFeed } from '@/live/liveScene'
import { toLiveReading } from '@/live/liveReading'
import '@/live/live.css'

/** A reading older than this is not live: the page fades and says so. */
const STALE_AFTER_MS = 2 * 60_000

/** Where readings come from: calls back with each /ws/power message and the connection state; returns a stop. */
export type PowerSource = (onPower: (p: LivePower) => void, onConnected: (connected: boolean) => void) => () => void

/** Live in the web app: readings from /ws/power on the signed-in session. */
export function LivePage() {
  return <LiveView source={followPower} />
}

/**
 * Live: where the house's power is coming from and going to right now, as a 3D house or a flow diagram. The picture
 * takes the full width. Built from docs/prototypes/live-page.html (see web/scripts/port-live-prototype.py). The Android
 * app shows the same view (live-embed.html), fed by the app's own connection.
 */
export function LiveView({ source }: { source: PowerSource }) {
  // The effects switches are for trying things out: only with debug=true in the address.
  const debug = new URLSearchParams(window.location.search).get('debug') === 'true'
  const feed = useRef<LiveFeed | null>(null)
  const [power, setPower] = useState<LivePower | null>(null)

  useEffect(() => {
    const live = startLive()
    feed.current = live
    let latest: LivePower | null = null
    let lastAt = 0
    // Stopped: no house reading, nothing for two minutes, or the inverter gone quiet (a live house reading but no flows).
    const stopped = (p: LivePower | null) => p == null || p.watts == null || (p.flows != null && p.flows.length === 0 && p.watts > 0)
    const judge = () => live.setStale(stopped(latest) || Date.now() - lastAt > STALE_AFTER_MS)
    const stopPower = source(
      (p) => {
        latest = p
        setPower(p)
        live.setWeather(p.weather?.look)
        lastAt = Date.now()
        if (!stopped(p)) live.push(toLiveReading(p))
        judge()
      },
      () => judge(),
    )
    const timer = window.setInterval(judge, 5000)
    return () => {
      window.clearInterval(timer)
      stopPower()
      live.stop()
      feed.current = null
    }
  }, [source])

  return (
    <div className="live-page" data-testid="live-page">
      <div className="wrap">
        <header>
          <div className="title">
            <h1>Live</h1>
            <span className="status" id="status">
              <span className="dot" />
              <span id="statusText">Connecting…</span>
            </span>
          </div>
          <div className="seg" role="group" aria-label="View">
            <button id="vHouse" aria-pressed="true">House</button>
            <button id="vDiagram" aria-pressed="false">Diagram</button>
          </div>
        </header>

        <div className="main">
          <div className="stagecol">
            <div className="stage" id="stage">
              <div className="track" id="track">
                <div className="pane" id="paneHouse">
                  <canvas id="gl" aria-label="3D model of the house with its power flows" />
                  <div id="labels" />
                </div>
                <div className="pane" id="paneDiagram">
                  <div id="svgwrap" />
                </div>
              </div>
              <div className="stalenote" id="staleNote" hidden />
              <div className="hint" id="orbitHint">Drag to turn · pinch or scroll to zoom</div>
            </div>
            <div className="viewbar">
              <button className="reset" id="resetView" hidden>Reset view</button>
              {debug && (
                <button className="reset" id="fxBtn" aria-expanded="false">
                  Effects
                </button>
              )}
            </div>
            {debug && <div className="fx" id="fx" hidden />}
          </div>

          {/* What is using the power, full width under the picture. */}
          <div className="league">
            <LeagueBoard live={power} wide />
          </div>

          {/* The house at a glance: home, grid and battery. */}
          <div className="panel dialpanel">
            <LiveDial live={power} />
          </div>
        </div>
      </div>
    </div>
  )
}
