// Shown by the service worker when the heating cannot be reached: Try again reloads the page the
// person was opening. A separate file because the Content-Security-Policy allows no inline script.
document.getElementById('retry').addEventListener('click', () => window.location.reload())
