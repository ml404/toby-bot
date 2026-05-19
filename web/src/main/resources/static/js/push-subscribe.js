// "Enable browser push for this device" toggle.
//
// Lifecycle:
//   1. On DOMContentLoaded, fetch /api/push/vapid-public-key.
//      - 404 → server hasn't configured VAPID. Hide the toggle and bail.
//      - 200 → fall through.
//   2. Inspect `Notification.permission` + the existing service-worker
//      subscription. Show the toggle in the right initial state
//      (enabled/disabled).
//   3. On click:
//      - if no subscription: request permission, register /sw.js, call
//        pushManager.subscribe(...), POST to /api/push/subscribe.
//      - if subscribed: unsubscribe locally, DELETE /api/push/subscribe.
//
// Failure modes:
//   - permission denied → toggle reverts; surface a hint.
//   - service worker registration fails → revert + log.
//   - server delete returns non-2xx → still unsubscribe locally so the
//     user's intent wins; the next sync will reconcile.
//
// The element this script wires expects:
//   <section data-push-toggle>
//     <button type="button" class="push-toggle-btn">Enable</button>
//     <p class="push-toggle-status muted"></p>
//   </section>
(function () {
    'use strict';

    document.addEventListener('DOMContentLoaded', async function () {
        const root = document.querySelector('[data-push-toggle]');
        if (!root) return;
        const btn = root.querySelector('.push-toggle-btn');
        const status = root.querySelector('.push-toggle-status');
        if (!btn) return;

        if (!('serviceWorker' in navigator) || !('PushManager' in window)) {
            root.style.display = 'none';
            return;
        }

        let vapidPublicKey = null;
        try {
            const resp = await fetch('/api/push/vapid-public-key', { credentials: 'same-origin' });
            if (resp.status === 404) {
                root.style.display = 'none';
                return;
            }
            if (!resp.ok) throw new Error('vapid fetch failed');
            const body = await resp.json();
            vapidPublicKey = body.publicKey;
        } catch (_) {
            root.style.display = 'none';
            return;
        }

        let registration = null;
        try {
            registration = await navigator.serviceWorker.register('/sw.js');
        } catch (e) {
            setStatus('Service worker registration failed: ' + (e.message || e));
            btn.disabled = true;
            return;
        }
        await navigator.serviceWorker.ready;

        let subscription = await registration.pushManager.getSubscription();
        const testBtn = root.querySelector('[data-push-test]');
        if (testBtn) {
            testBtn.addEventListener('click', async function () {
                testBtn.disabled = true;
                setStatus('Sending test push…');
                try {
                    const token = getCsrfToken();
                    const headers = { 'Content-Type': 'application/json' };
                    if (token) headers[getCsrfHeader()] = token;
                    const resp = await fetch('/api/push/test', {
                        method: 'POST',
                        credentials: 'same-origin',
                        headers: headers,
                    });
                    // 503 / 500 still carry a JSON body explaining why.
                    const body = await resp.json().catch(function () { return null; });
                    if (body && body.message) {
                        setStatus(body.message);
                    } else {
                        setStatus('Test request returned HTTP ' + resp.status + '.');
                    }
                } catch (e) {
                    setStatus('Test request failed: ' + (e.message || e));
                } finally {
                    testBtn.disabled = false;
                }
            });
        }
        render();

        btn.addEventListener('click', async function () {
            btn.disabled = true;
            try {
                if (subscription) {
                    await unsubscribe();
                } else {
                    await subscribe();
                }
            } catch (e) {
                setStatus(e.message || String(e));
            } finally {
                btn.disabled = false;
                render();
            }
        });

        async function subscribe() {
            const permission = await Notification.requestPermission();
            if (permission !== 'granted') {
                throw new Error('Browser denied notification permission.');
            }
            subscription = await registration.pushManager.subscribe({
                userVisibleOnly: true,
                applicationServerKey: urlBase64ToUint8Array(vapidPublicKey)
            });
            const json = subscription.toJSON();
            const body = {
                endpoint: subscription.endpoint,
                p256dh: json.keys && json.keys.p256dh,
                auth: json.keys && json.keys.auth
            };
            const resp = await window.TobyApi.postJson('/api/push/subscribe', body);
            if (!resp || resp.ok === false) {
                // Server didn't persist — roll back the browser-side sub
                // so we don't end up in a state where the browser pings
                // a phantom endpoint nobody knows about.
                try { await subscription.unsubscribe(); } catch (_) {}
                subscription = null;
                throw new Error('Server rejected subscription.');
            }
        }

        async function unsubscribe() {
            const endpoint = subscription.endpoint;
            try { await subscription.unsubscribe(); } catch (_) {}
            subscription = null;
            // Best-effort server delete; we don't await its result for the
            // toggle to flip, but we want the row gone too.
            const token = getCsrfToken();
            const headers = { 'Content-Type': 'application/json' };
            if (token) headers[getCsrfHeader()] = token;
            try {
                await fetch('/api/push/subscribe', {
                    method: 'DELETE',
                    credentials: 'same-origin',
                    headers: headers,
                    body: JSON.stringify({ endpoint: endpoint })
                });
            } catch (_) { /* ignore — local unsub already happened */ }
        }

        function render() {
            // Only surface the smoke-test button once the user has a live
            // subscription on this device — otherwise it would always
            // return "no subscriptions registered" and waste a click.
            if (testBtn) testBtn.hidden = !subscription;
            if (subscription) {
                btn.textContent = 'Disable browser push';
                btn.classList.add('is-on');
                btn.classList.remove('is-off');
                setStatus('Push is enabled on this device.');
            } else if (Notification.permission === 'denied') {
                btn.textContent = 'Enable browser push';
                btn.classList.add('is-off');
                btn.classList.remove('is-on');
                btn.disabled = true;
                setStatus('Notifications are blocked in your browser settings.');
            } else {
                btn.textContent = 'Enable browser push';
                btn.classList.add('is-off');
                btn.classList.remove('is-on');
                setStatus('Not enabled on this device.');
            }
        }

        function setStatus(text) {
            if (status) status.textContent = text;
        }

        function getCsrfToken() {
            const meta = document.querySelector('meta[name="_csrf"]');
            return meta ? meta.content : '';
        }
        function getCsrfHeader() {
            const meta = document.querySelector('meta[name="_csrf_header"]');
            return meta ? meta.content : 'X-CSRF-TOKEN';
        }

        // Browsers expect applicationServerKey as a Uint8Array of the raw
        // P-256 public-key bytes; the server sends the base64url-encoded
        // string the same web-push tooling emits. Convert here so callers
        // don't need to think about it.
        function urlBase64ToUint8Array(base64String) {
            const padding = '='.repeat((4 - base64String.length % 4) % 4);
            const base64 = (base64String + padding).replace(/-/g, '+').replace(/_/g, '/');
            const raw = atob(base64);
            const out = new Uint8Array(raw.length);
            for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
            return out;
        }
    });
})();
