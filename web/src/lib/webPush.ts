/** This browser's Web Push subscription, if it has one (none outside a service worker). */
export async function currentPushSubscription(): Promise<PushSubscription | null> {
  try {
    if (!('serviceWorker' in navigator)) return null
    const registration = await navigator.serviceWorker.getRegistration('/')
    return (await registration?.pushManager?.getSubscription()) ?? null
  } catch {
    return null
  }
}

/** What decides whether this browser can get notifications as it is. */
export interface BrowserInfo {
  userAgent: string
  maxTouchPoints: number
  /** Opened from the home screen (an installed app) rather than in a browser tab. */
  standalone: boolean
}

export function thisBrowser(): BrowserInfo {
  const standalone =
    (navigator as Navigator & { standalone?: boolean }).standalone === true || window.matchMedia?.('(display-mode: standalone)').matches === true
  return { userAgent: navigator.userAgent, maxTouchPoints: navigator.maxTouchPoints ?? 0, standalone }
}

/**
 * True on an iPhone or iPad outside the home screen app: iOS gives web pages notifications only
 * once they are added to the home screen. iPadOS asks for desktop pages and calls itself a Mac,
 * so a "Mac" with a touch screen counts too.
 */
export function needsHomeScreen({ userAgent, maxTouchPoints, standalone }: BrowserInfo): boolean {
  if (standalone) return false
  return /\b(iPhone|iPad|iPod)\b/.test(userAgent) || (/\bMacintosh\b/.test(userAgent) && maxTouchPoints > 1)
}

/** The browser has what Web Push needs: a service worker, PushManager and notifications. */
export function pushSupported(): boolean {
  return 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window
}

/** The service's VAPID public key (base64url) as the bytes pushManager.subscribe takes. */
export function applicationServerKey(publicKey: string): Uint8Array<ArrayBuffer> {
  const base64 = publicKey.replace(/-/g, '+').replace(/_/g, '/')
  const binary = atob(base64 + '='.repeat((4 - (base64.length % 4)) % 4))
  const bytes = new Uint8Array(new ArrayBuffer(binary.length))
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return bytes
}
