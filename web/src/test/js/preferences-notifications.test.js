// Drives the /preferences/notifications matrix toggle JS end-to-end
// with a stubbed TobyApi.postJson. Mirrors the casino-game lock test
// pattern: real source file required, stubbed network, fake DOM,
// jsdom event dispatch to fire the DOMContentLoaded wiring.

describe('preferences-notifications.js — matrix toggle wiring', () => {
    let postJsonMock;

    /** Fresh DOM + fresh module load before each test. */
    function loadModule() {
        // Reset jest's module cache so the IIFE re-runs against the
        // fresh DOM/window/TobyApi state.
        jest.resetModules();
        require('../../main/resources/static/js/preferences-notifications');
        // The script wires its work inside a DOMContentLoaded listener.
        // jsdom's default readyState is 'complete' so DOMContentLoaded
        // already fired before our require; manually dispatch so the
        // listener attaches its handlers.
        document.dispatchEvent(new Event('DOMContentLoaded'));
    }

    beforeEach(() => {
        document.body.innerHTML =
            '<main data-guild-id="42">' +
            '  <button class="notif-toggle is-on"' +
            '          data-kind="ACHIEVEMENT_UNLOCK"' +
            '          data-surface="DM"' +
            '          data-opt-in="true"' +
            '          data-default="true">On</button>' +
            '  <button class="notif-toggle is-off"' +
            '          data-kind="STREAK_REMINDER"' +
            '          data-surface="DM"' +
            '          data-opt-in="false"' +
            '          data-default="true">Off</button>' +
            '  <span class="notif-placeholder muted" data-surface="CHANNEL">—</span>' +
            '</main>';

        postJsonMock = jest.fn();
        window.TobyApi = { postJson: postJsonMock };
        loadModule();
    });

    afterEach(() => {
        delete window.TobyApi;
        document.body.innerHTML = '';
    });

    test('click on an On toggle POSTs optIn=false to the right URL', () => {
        postJsonMock.mockReturnValue(new Promise(() => {})); // never resolves
        const button = document.querySelector(
            '.notif-toggle[data-kind="ACHIEVEMENT_UNLOCK"]'
        );
        button.click();

        expect(postJsonMock).toHaveBeenCalledTimes(1);
        expect(postJsonMock).toHaveBeenCalledWith(
            '/api/engagement/42/notifications/ACHIEVEMENT_UNLOCK/DM',
            { optIn: false }
        );
    });

    test('click on an Off toggle POSTs optIn=true', () => {
        postJsonMock.mockReturnValue(new Promise(() => {}));
        const button = document.querySelector(
            '.notif-toggle[data-kind="STREAK_REMINDER"]'
        );
        button.click();

        expect(postJsonMock).toHaveBeenCalledWith(
            '/api/engagement/42/notifications/STREAK_REMINDER/DM',
            { optIn: true }
        );
    });

    test('optimistic update flips the cell class and label immediately', () => {
        postJsonMock.mockReturnValue(new Promise(() => {}));
        const button = document.querySelector(
            '.notif-toggle[data-kind="ACHIEVEMENT_UNLOCK"]'
        );
        // Pre-click: is-on.
        expect(button.classList.contains('is-on')).toBe(true);
        expect(button.classList.contains('is-off')).toBe(false);
        expect(button.textContent).toBe('On');

        button.click();

        // Optimistic: flipped before the network resolves.
        expect(button.classList.contains('is-on')).toBe(false);
        expect(button.classList.contains('is-off')).toBe(true);
        expect(button.textContent).toBe('Off');
        expect(button.getAttribute('data-opt-in')).toBe('false');
        // Disabled while in-flight so the user can't double-click.
        expect(button.disabled).toBe(true);
    });

    test('successful POST keeps the optimistic state and re-enables the button', async () => {
        postJsonMock.mockResolvedValue({ ok: true });
        const button = document.querySelector(
            '.notif-toggle[data-kind="STREAK_REMINDER"]'
        );
        button.click();

        // Flush the resolved promise.
        await Promise.resolve();
        await Promise.resolve();

        expect(button.classList.contains('is-on')).toBe(true);
        expect(button.classList.contains('is-off')).toBe(false);
        expect(button.textContent).toBe('On');
        expect(button.disabled).toBe(false);
    });

    test('failed POST reverts the cell to its previous state', async () => {
        postJsonMock.mockResolvedValue({ ok: false, error: 'server died' });
        const button = document.querySelector(
            '.notif-toggle[data-kind="ACHIEVEMENT_UNLOCK"]'
        );
        button.click();

        // Optimistic flip first.
        expect(button.classList.contains('is-off')).toBe(true);

        // Flush the resolved promise.
        await Promise.resolve();
        await Promise.resolve();

        // Reverted to the original state.
        expect(button.classList.contains('is-on')).toBe(true);
        expect(button.classList.contains('is-off')).toBe(false);
        expect(button.textContent).toBe('On');
        expect(button.disabled).toBe(false);
    });

    test('network-level rejection reverts the cell', async () => {
        postJsonMock.mockRejectedValue(new Error('offline'));
        const button = document.querySelector(
            '.notif-toggle[data-kind="STREAK_REMINDER"]'
        );
        button.click();

        // Flush rejection (catch handler runs).
        await Promise.resolve();
        await Promise.resolve();

        expect(button.classList.contains('is-off')).toBe(true);
        expect(button.textContent).toBe('Off');
        expect(button.disabled).toBe(false);
    });

    test('placeholder cells are not clickable / do not POST', () => {
        postJsonMock.mockReturnValue(new Promise(() => {}));
        const placeholder = document.querySelector('.notif-placeholder');
        placeholder.click();

        expect(postJsonMock).not.toHaveBeenCalled();
    });

    test('disabled toggle (in-flight) ignores additional clicks', () => {
        postJsonMock.mockReturnValue(new Promise(() => {}));
        const button = document.querySelector(
            '.notif-toggle[data-kind="ACHIEVEMENT_UNLOCK"]'
        );

        button.click(); // first click — fires request, disables button
        button.click(); // second click — should be ignored (button.disabled true)

        expect(postJsonMock).toHaveBeenCalledTimes(1);
    });

    test('no main with data-guild-id → no listeners wired', () => {
        // Wipe + re-load against a DOM without the marker element.
        document.body.innerHTML =
            '<button class="notif-toggle"' +
            '        data-kind="ACHIEVEMENT_UNLOCK"' +
            '        data-surface="DM"' +
            '        data-opt-in="true">On</button>';
        loadModule();

        const button = document.querySelector('.notif-toggle');
        button.click();
        expect(postJsonMock).not.toHaveBeenCalled();
    });
});
