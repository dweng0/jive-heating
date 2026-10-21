// The heating web app's service worker. It is served from the root with Cache-Control: no-cache,
// so browsers always pick up a new version. It caches only the offline page: everything else,
// and above all /api and /ws, always comes from the service, so nothing it shows is ever stale.
// It also shows Web Push notifications, and a tap opens the room they are about.

importScripts('/notifications.js')

const CACHE = 'heating-offline-v1'
const OFFLINE = '/offline.html'
const PRECACHE = [OFFLINE, '/offline.js', '/icon-192.png']

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(CACHE)
      .then((cache) => cache.addAll(PRECACHE))
      .then(() => self.skipWaiting()),
  )
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
      .then(() => self.clients.claim()),
  )
})

const isApi = (url) => url.pathname === '/api' || url.pathname.startsWith('/api/') || url.pathname === '/ws'

// A page load that cannot reach the service (no connection, or the tunnel saying the service is
// down) gets the offline page instead of the browser's error page. The offline page's own script
// and icon come from the cache when the network fails. Other requests are left alone.
self.addEventListener('fetch', (event) => {
  const request = event.request
  const url = new URL(request.url)
  if (url.origin !== self.location.origin || isApi(url)) return
  if (request.mode === 'navigate') {
    event.respondWith(
      fetch(request)
        .then((response) => (response.status >= 502 && response.status <= 504 ? offline() : response))
        .catch(() => offline()),
    )
  } else if (PRECACHE.includes(url.pathname)) {
    event.respondWith(fetch(request).catch(() => caches.match(url.pathname).then((cached) => cached ?? Response.error())))
  }
})

const offline = () => caches.match(OFFLINE).then((page) => page ?? Response.error())

// A notification from the service (Web Push). Browsers require every push to show one.
self.addEventListener('push', (event) => {
  let data = {}
  try {
    data = event.data ? event.data.json() : {}
  } catch {
    data = { body: event.data ? event.data.text() : '' }
  }
  const { title, options } = self.heatingNotification.show(data)
  event.waitUntil(self.registration.showNotification(title, options))
})

// A tap opens the app on the notification's room: in a window that is already open if there is one.
self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  const url = new URL(event.notification.data?.url ?? '/', self.location.origin).href
  event.waitUntil(
    (async () => {
      const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
      const open = windows.find((client) => new URL(client.url).origin === self.location.origin)
      if (open) {
        const focused = await open.focus().catch(() => open)
        const navigated = await focused.navigate(url).catch(() => null)
        if (navigated) return
      }
      await self.clients.openWindow(url)
    })(),
  )
})
