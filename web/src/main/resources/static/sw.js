// TobyBot push service worker.
//
// Served from the origin root (`/sw.js`) so its scope covers every page —
// a service worker can only control pages at or below the path it was
// registered from, so anything nested would silently drop pushes.
//
// Two events:
//   push          → render a system notification from the JSON envelope
//                   `{ title, body, deepLink }` sent by WebPushAdapter,
//                   UNLESS a tab is currently visible — in that case the
//                   in-page toast (delivered via SSE by
//                   `notifications-stream.js`) is already showing the
//                   user, so a parallel OS banner is redundant noise.
//   notificationclick → focus an existing tab on the deep link if open,
//                   otherwise open a new one. Falls back to the origin
//                   root when no deepLink was supplied.
'use strict';

self.addEventListener('push', function (event) {
    let data = { title: 'TobyBot', body: '', deepLink: null };
    if (event.data) {
        try { data = Object.assign(data, event.data.json()); }
        catch (_) { data.body = event.data.text(); }
    }
    const options = {
        body: data.body || '',
        // No icon / badge: the asset they used to point at
        // (/images/toby-icon.png) doesn't exist, and macOS Firefox
        // routes showNotification through the system NotificationCenter,
        // which silently rejects the call when an icon URL 404s
        // instead of falling back to a default. Omitting the fields
        // lets every browser substitute its own default app glyph.
        data: { deepLink: data.deepLink || '/' }
    };
    event.waitUntil((async () => {
        // Foreground suppression: if any same-origin tab is visible the
        // in-page toast already covers the user, so don't pop an OS
        // banner on top of it. Chromium honours skipping showNotification
        // when a client is visible; Firefox may show a generic
        // "site updated in the background" fallback in rare cases — the
        // tradeoff vs. the duplicate-surface UX is worth it.
        const windowClients = await self.clients.matchAll({
            type: 'window',
            includeUncontrolled: true
        });
        const visible = windowClients.some(function (c) {
            return c.visibilityState === 'visible';
        });
        if (visible) return;
        return self.registration.showNotification(data.title || 'TobyBot', options);
    })());
});

self.addEventListener('notificationclick', function (event) {
    event.notification.close();
    const target = (event.notification.data && event.notification.data.deepLink) || '/';
    event.waitUntil((async () => {
        const all = await clients.matchAll({ type: 'window', includeUncontrolled: true });
        const url = new URL(target, self.location.origin).href;
        for (const c of all) {
            if (c.url === url && 'focus' in c) return c.focus();
        }
        if (clients.openWindow) return clients.openWindow(url);
    })());
});
