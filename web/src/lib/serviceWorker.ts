/**
 * Registers public/sw.js for the whole site, so an app added to the home screen opens with a
 * friendly page when the heating cannot be reached and receives Web Push notifications.
 * Not in `npm run dev`, where a worker would get in the way of hot reloading.
 */
export function registerServiceWorker() {
  if (!import.meta.env.PROD || !('serviceWorker' in navigator)) return
  window.addEventListener('load', () => {
    // updateViaCache 'none': the scripts the worker imports are revalidated like the worker itself.
    navigator.serviceWorker.register('/sw.js', { scope: '/', updateViaCache: 'none' }).catch(() => undefined)
  })
}
