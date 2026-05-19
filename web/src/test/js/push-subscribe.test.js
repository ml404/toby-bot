// Drives push-subscribe.js end-to-end with stubbed fetch + ServiceWorker
// + PushManager + Notification globals. Mirrors the preferences-notifications
// test pattern: real source file required, stubbed network, jsdom DOM,
// manual DOMContentLoaded dispatch so the module's listener fires.
//
// Each test does a full DOM reset and reattaches its own DOMContentLoaded
// listener via require — listeners from previous tests remain on the
// document object but they all reference globals (`fetch`, `navigator`,
// `Notification`, `window.TobyApi`) by name at call time, not by capture,
// so they all converge on the same per-test mocks. We use stable
// `mockResolvedValue` (not `mockResolvedValueOnce`) for the same reason:
// multiple replays of the listener fan-out shouldn't drain a one-shot
// queue and leave later replays seeing fall-through defaults.

describe('push-subscribe.js — browser-push toggle wiring', () => {
    let fetchMock;
    let postJsonMock;
    let registerMock;
    let getSubMock;
    let subscribeMock;
    let unsubscribeMock;
    let registration;
    let originalNotification;
    let originalServiceWorker;
    let originalPushManager;

    function loadModule() {
        jest.resetModules();
        require('../../main/resources/static/js/push-subscribe');
        document.dispatchEvent(new Event('DOMContentLoaded'));
    }

    /** Wait for any reasonable number of microtask chains to settle. */
    async function flush() {
        for (let i = 0; i < 12; i++) await Promise.resolve();
    }

    beforeEach(() => {
        document.body.innerHTML =
            '<section data-push-toggle>' +
            '  <button type="button" class="push-toggle-btn">Enable</button>' +
            '  <p class="push-toggle-status muted"></p>' +
            '</section>';

        // Default fetch: VAPID key endpoint returns 200 with a key. The
        // DELETE branch of the script also uses fetch (separate URL) —
        // it's a no-op response.
        fetchMock = jest.fn().mockImplementation((url, opts) => {
            if (url === '/api/push/vapid-public-key') {
                return Promise.resolve({
                    ok: true,
                    status: 200,
                    json: async () => ({ publicKey: 'BAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' }),
                });
            }
            return Promise.resolve({ ok: true, status: 204, json: async () => ({}) });
        });
        global.fetch = fetchMock;

        postJsonMock = jest.fn().mockResolvedValue({ ok: true });
        window.TobyApi = { postJson: postJsonMock };

        getSubMock = jest.fn().mockResolvedValue(null);
        subscribeMock = jest.fn();
        unsubscribeMock = jest.fn().mockResolvedValue(undefined);
        registration = {
            pushManager: { getSubscription: getSubMock, subscribe: subscribeMock },
        };
        registerMock = jest.fn().mockResolvedValue(registration);

        originalServiceWorker = Object.getOwnPropertyDescriptor(navigator, 'serviceWorker');
        Object.defineProperty(navigator, 'serviceWorker', {
            configurable: true,
            value: { register: registerMock, ready: Promise.resolve(registration) },
        });

        originalPushManager = window.PushManager;
        window.PushManager = function () {};

        originalNotification = global.Notification;
        global.Notification = {
            permission: 'default',
            requestPermission: jest.fn().mockResolvedValue('granted'),
        };
        window.Notification = global.Notification;
    });

    afterEach(() => {
        document.body.innerHTML = '';
        delete window.TobyApi;
        if (originalServiceWorker) {
            Object.defineProperty(navigator, 'serviceWorker', originalServiceWorker);
        } else {
            // jsdom default has no serviceWorker; clean ours up.
            delete navigator.serviceWorker;
        }
        window.PushManager = originalPushManager;
        global.Notification = originalNotification;
        window.Notification = originalNotification;
        delete global.fetch;
    });

    test('hides the section when VAPID key endpoint returns 404', async () => {
        fetchMock.mockImplementation((url) => {
            if (url === '/api/push/vapid-public-key') {
                return Promise.resolve({ ok: false, status: 404, json: async () => ({}) });
            }
            return Promise.resolve({ ok: true, status: 204, json: async () => ({}) });
        });
        loadModule();
        await flush();
        const section = document.querySelector('[data-push-toggle]');
        expect(section.style.display).toBe('none');
        expect(registerMock).not.toHaveBeenCalled();
    });

    test('hides the section when PushManager is not supported', async () => {
        // Simulate a browser without the Push API.
        delete window.PushManager;
        loadModule();
        await flush();
        const section = document.querySelector('[data-push-toggle]');
        expect(section.style.display).toBe('none');
        expect(registerMock).not.toHaveBeenCalled();
    });

    test('renders "Enable" state on load when no subscription is present', async () => {
        loadModule();
        await flush();
        const btn = document.querySelector('.push-toggle-btn');
        expect(btn.textContent).toBe('Enable browser push');
        expect(btn.classList.contains('is-off')).toBe(true);
        expect(btn.disabled).toBe(false);
    });

    test('renders "Disable" state on load when an existing subscription is present', async () => {
        getSubMock.mockResolvedValue({
            endpoint: 'https://existing',
            unsubscribe: unsubscribeMock,
            toJSON: () => ({ endpoint: 'https://existing', keys: { p256dh: 'p', auth: 'a' } }),
        });
        loadModule();
        await flush();
        const btn = document.querySelector('.push-toggle-btn');
        expect(btn.textContent).toBe('Disable browser push');
        expect(btn.classList.contains('is-on')).toBe(true);
    });

    test('clicking Enable subscribes and POSTs to /api/push/subscribe', async () => {
        const newSub = {
            endpoint: 'https://new',
            unsubscribe: jest.fn().mockResolvedValue(undefined),
            toJSON: () => ({ endpoint: 'https://new', keys: { p256dh: 'pk-bytes', auth: 'auth-bytes' } }),
        };
        subscribeMock.mockResolvedValue(newSub);

        loadModule();
        await flush();

        document.querySelector('.push-toggle-btn').click();
        await flush();

        expect(global.Notification.requestPermission).toHaveBeenCalled();
        expect(subscribeMock).toHaveBeenCalledWith(expect.objectContaining({ userVisibleOnly: true }));
        expect(postJsonMock).toHaveBeenCalledWith('/api/push/subscribe', {
            endpoint: 'https://new',
            p256dh: 'pk-bytes',
            auth: 'auth-bytes',
        });
        const btn = document.querySelector('.push-toggle-btn');
        expect(btn.textContent).toBe('Disable browser push');
        expect(btn.classList.contains('is-on')).toBe(true);
    });

    test('denied permission surfaces a hint and does not POST', async () => {
        // Match real browser: requestPermission resolves 'denied' AND
        // Notification.permission flips to 'denied' for the lifetime
        // of the page.
        global.Notification.requestPermission = jest.fn().mockImplementation(() => {
            global.Notification.permission = 'denied';
            return Promise.resolve('denied');
        });
        loadModule();
        await flush();

        document.querySelector('.push-toggle-btn').click();
        await flush();

        expect(postJsonMock).not.toHaveBeenCalled();
        expect(subscribeMock).not.toHaveBeenCalled();
        const status = document.querySelector('.push-toggle-status');
        // "Notifications are blocked in your browser settings." — the
        // render branch for Notification.permission === 'denied'.
        expect(status.textContent.toLowerCase()).toContain('blocked');
        // The toggle is disabled so the user can't keep trying.
        expect(document.querySelector('.push-toggle-btn').disabled).toBe(true);
    });

    test('server rejection rolls back the browser subscription', async () => {
        const rolledBack = jest.fn().mockResolvedValue(undefined);
        subscribeMock.mockResolvedValue({
            endpoint: 'https://new',
            unsubscribe: rolledBack,
            toJSON: () => ({ endpoint: 'https://new', keys: { p256dh: 'p', auth: 'a' } }),
        });
        postJsonMock.mockResolvedValue({ ok: false, error: 'rejected' });

        loadModule();
        await flush();
        document.querySelector('.push-toggle-btn').click();
        await flush();

        // The local pushManager subscription got undone so we don't leave
        // the browser pinging an endpoint the server doesn't know.
        expect(rolledBack).toHaveBeenCalled();
        const btn = document.querySelector('.push-toggle-btn');
        expect(btn.classList.contains('is-off')).toBe(true);
    });

    test('clicking Disable unsubscribes locally and DELETEs server-side', async () => {
        const existing = {
            endpoint: 'https://existing',
            unsubscribe: unsubscribeMock,
            toJSON: () => ({ endpoint: 'https://existing', keys: { p256dh: 'p', auth: 'a' } }),
        };
        getSubMock.mockResolvedValue(existing);

        loadModule();
        await flush();

        // The toggle should be in "Disable" state.
        const btn = document.querySelector('.push-toggle-btn');
        expect(btn.textContent).toBe('Disable browser push');

        btn.click();
        await flush();

        expect(unsubscribeMock).toHaveBeenCalled();
        // DELETE goes through fetch directly, not TobyApi.postJson.
        const deleteCall = fetchMock.mock.calls.find(c => c[0] === '/api/push/subscribe');
        expect(deleteCall).toBeDefined();
        expect(deleteCall[1].method).toBe('DELETE');
        expect(JSON.parse(deleteCall[1].body)).toEqual({ endpoint: 'https://existing' });
    });
});
