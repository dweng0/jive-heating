// The Live view inside the Android app (assets/live/live-embed.html). The app streams /ws/power itself and hands each
// message over with window.liveFeed.push(json); window.liveFeed.connected(false) when its connection drops.
import { createRoot } from 'react-dom/client'
import '@fontsource-variable/plus-jakarta-sans'
import '@/index.css'
import type { LivePower } from '@/lib/livePower'
import { LiveView, type PowerSource } from '@/pages/Live'

declare global {
  interface Window {
    liveFeed?: { push(p: LivePower | string): void; connected(c: boolean): void }
  }
}

const fromApp: PowerSource = (onPower, onConnected) => {
  window.liveFeed = {
    push: (p) => onPower(typeof p === 'string' ? (JSON.parse(p) as LivePower) : p),
    connected: onConnected,
  }
  return () => { window.liveFeed = undefined }
}

// The app's colours follow the phone's light or dark setting, as in the web app.
const dark = window.matchMedia('(prefers-color-scheme: dark)')
const applyTheme = () => document.documentElement.classList.toggle('dark', dark.matches)
applyTheme()
dark.addEventListener('change', applyTheme)

createRoot(document.getElementById('root')!).render(<LiveView source={fromApp} />)
