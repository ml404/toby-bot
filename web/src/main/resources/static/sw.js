// TobyBot push service worker.
//
// Served from the origin root (`/sw.js`) so its scope covers every page —
// a service worker can only control pages at or below the path it was
// registered from, so anything nested would silently drop pushes.
//
// Two events:
//   push          → render a system notification from the JSON envelope
//                   `{ title, body, deepLink }` sent by WebPushAdapter.
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
        icon: '/images/toby-icon.png',
        badge: '/images/toby-icon.png',
        data: { deepLink: data.deepLink || '/' }
    };
    event.waitUntil(self.registration.showNotification(data.title || 'TobyBot', options));
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
