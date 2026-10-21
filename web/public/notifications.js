// What a Web Push notification shows and where tapping it goes. The service worker loads this with
// importScripts; it is a plain script so the browser tests can load it into a page too.
// A push's JSON is {title, body, roomId}: roomId names the one room it is about, or is null.

self.heatingNotification = {
  /** The app's page for a notification: its room, or the Rooms screen when it names several or none. */
  url(data) {
    const roomId = data && typeof data.roomId === 'string' && data.roomId !== '' ? data.roomId : null
    return roomId ? `/rooms/${encodeURIComponent(roomId)}` : '/'
  },

  /** The arguments for showNotification from a push's JSON. */
  show(data) {
    const payload = data && typeof data === 'object' ? data : {}
    return {
      title: typeof payload.title === 'string' && payload.title !== '' ? payload.title : 'Heating',
      options: {
        body: typeof payload.body === 'string' ? payload.body : '',
        icon: '/icon-192.png',
        lang: 'en-GB',
        data: { url: self.heatingNotification.url(payload) },
      },
    }
  },
}
