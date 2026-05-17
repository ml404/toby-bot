// Tests for the in-browser preview controller — clicking ▶ next to a
// search result or queue item plays the source's own ~30s clip via a
// shared <audio> element. Spotify / Apple Music / Deezer / Yandex (all
// surfaced via LavaSrc's ExtendedAudioTrack#getPreviewUrl) get a button;
// YouTube / SoundCloud / Bandcamp / HTTP / Local don't.
//
// We boot the music-player.js IIFE against a hand-crafted DOM, stub
// fetch + EventSource + HTMLMediaElement.play / .pause, then drive the
// SSE / DOM events the production code listens to.

class MockEventSource {
    constructor(url) {
        this.url = url;
        this.handlers = {};
        this.closed = false;
        MockEventSource.instances.push(this);
    }
    addEventListener(name, handler) {
        this.handlers[name] = handler;
    }
    close() { this.closed = true; }
    fire(name, payload) {
        const handler = this.handlers[name];
        if (handler) handler({ data: JSON.stringify(payload) });
    }
}
MockEventSource.instances = [];

function flush() {
    return new Promise((resolve) => setTimeout(resolve, 0));
}

function setUpDashboardDom() {
    document.head.innerHTML = '';
    document.body.innerHTML = `
        <div id="now-playing-empty"></div>
        <div id="now-playing-content" hidden>
            <img id="now-playing-art" />
            <span id="source-badge"></span>
            <h3 id="now-playing-title"></h3>
            <p id="now-playing-author"></p>
            <div id="now-playing-requester" hidden>
                <span id="now-playing-requester-cell"></span>
            </div>
            <span id="time-current"></span>
            <span id="time-total"></span>
            <div id="progress-track"><div id="progress-fill"></div></div>
        </div>
        <button id="btn-pause">⏯️</button>
        <button id="btn-skip">⏭️</button>
        <button id="btn-stop">⏹️</button>
        <button id="btn-loop" aria-pressed="false">🔁</button>
        <input id="volume-slider" type="range" min="0" max="150" value="100" />
        <span id="volume-value">100</span>
        <form id="add-track-form"><input id="add-track-input" /><button>Search</button></form>
        <section id="search-results-section" hidden>
            <span id="search-results-query"></span>
            <button id="search-results-close"></button>
            <ol id="search-results-list"></ol>
            <p id="search-results-empty" hidden></p>
        </section>
        <ol id="queue-list"></ol>
        <p id="queue-empty"></p>
        <ul id="playlist-list"></ul>
        <p id="playlists-empty"></p>
        <form id="save-playlist-form"><input id="save-playlist-name" /><button>Save</button></form>
        <p id="voice-channel-name" hidden></p>
        <p id="voice-channel-empty"></p>
        <ul id="voice-member-list"></ul>
        <audio id="preview-audio" preload="none" hidden></audio>
    `;
    document.body.dataset.musicPage = '1';
    document.body.dataset.guildId = '42';
    document.body.dataset.discordId = '100';
}

function track(overrides) {
    return Object.assign({
        identifier: 'abc',
        title: 'Test Title',
        author: 'Test Author',
        durationMs: 200_000,
        uri: 'https://example.com/track',
        artworkUrl: null,
        sourceName: 'spotify',
        isStream: false,
        requesterDiscordId: null,
        previewUrl: 'https://p.scdn.co/mp3-preview/abc',
    }, overrides);
}

function idleState() {
    return {
        guildId: 42,
        nowPlaying: null,
        positionMs: 0,
        paused: false,
        volume: 100,
        looping: false,
        queue: [],
        voiceChannelId: null,
        voiceChannel: null,
    };
}

describe('music-player.js — preview controller', () => {
    let fetchMock;
    let visibilitySpy;
    let playMock;
    let pauseMock;
    let originalPlay;
    let originalPause;
    // Track the current paused state ourselves — jsdom's HTMLMediaElement
    // returns paused=true unconditionally, but the controller's toggle()
    // branches on it, so the test mocks have to flip it to match the
    // intended play/pause sequence.
    let isPaused;

    beforeEach(() => {
        jest.resetModules();
        MockEventSource.instances.length = 0;

        setUpDashboardDom();
        window.EventSource = MockEventSource;

        fetchMock = jest.fn().mockImplementation((url) => {
            if (typeof url === 'string' && url.endsWith('/state')) {
                return Promise.resolve({
                    ok: true,
                    json: () => Promise.resolve({ state: idleState() }),
                });
            }
            if (typeof url === 'string' && url.endsWith('/playlists')) {
                return Promise.resolve({ ok: true, json: () => Promise.resolve([]) });
            }
            return Promise.resolve({ ok: true, json: () => Promise.resolve({}) });
        });
        window.fetch = fetchMock;

        visibilitySpy = jest.spyOn(document, 'visibilityState', 'get');
        visibilitySpy.mockReturnValue('visible');

        // jsdom's <audio> doesn't actually play — stub play/pause so they
        // (a) fire the corresponding events the production code listens to
        // and (b) flip our isPaused mirror so audio.paused reflects reality
        // when the controller branches on it.
        originalPlay = HTMLMediaElement.prototype.play;
        originalPause = HTMLMediaElement.prototype.pause;
        isPaused = true;
        playMock = jest.fn().mockImplementation(function () {
            isPaused = false;
            this.dispatchEvent(new Event('play'));
            return Promise.resolve();
        });
        pauseMock = jest.fn().mockImplementation(function () {
            isPaused = true;
            this.dispatchEvent(new Event('pause'));
        });
        HTMLMediaElement.prototype.play = playMock;
        HTMLMediaElement.prototype.pause = pauseMock;
        Object.defineProperty(HTMLMediaElement.prototype, 'paused', {
            configurable: true,
            get: () => isPaused,
        });

        require('../../main/resources/static/js/music-player');
    });

    afterEach(() => {
        visibilitySpy.mockRestore();
        HTMLMediaElement.prototype.play = originalPlay;
        HTMLMediaElement.prototype.pause = originalPause;
        // Remove the paused override so it doesn't leak into the next file.
        delete HTMLMediaElement.prototype.paused;
    });

    test('search result with previewUrl renders a Preview button', async () => {
        await flush();
        const es = MockEventSource.instances[0];

        // Simulate a queueChanged so that the queue list re-renders (not
        // strictly necessary for search but flushes the IIFE setup).
        es.fire('queueChanged', { guildId: 42, queue: [] });
        await flush();

        // Drive the search by hand: call into renderSearchResults via the
        // public submit flow. Stub /search to return our fixture.
        fetchMock.mockImplementationOnce(() => Promise.resolve({
            ok: true,
            json: () => Promise.resolve([track()]),
        }));
        document.getElementById('add-track-input').value = 'linkin park';
        document.getElementById('add-track-form').dispatchEvent(new Event('submit', { cancelable: true }));
        await flush();

        const preview = document.querySelector('#search-results-list .btn-preview');
        expect(preview).not.toBeNull();
        expect(preview.textContent).toBe('▶');
    });

    test('search result without previewUrl renders no Preview button', async () => {
        await flush();
        fetchMock.mockImplementationOnce(() => Promise.resolve({
            ok: true,
            json: () => Promise.resolve([track({ sourceName: 'youtube', previewUrl: null })]),
        }));
        document.getElementById('add-track-input').value = 'something youtube';
        document.getElementById('add-track-form').dispatchEvent(new Event('submit', { cancelable: true }));
        await flush();

        expect(document.querySelector('#search-results-list .search-result')).not.toBeNull();
        expect(document.querySelector('#search-results-list .btn-preview')).toBeNull();
    });

    test('queue item with previewUrl renders a Preview button', async () => {
        await flush();
        const es = MockEventSource.instances[0];
        es.fire('queueChanged', { guildId: 42, queue: [track()] });
        await flush();

        const preview = document.querySelector('#queue-list .btn-preview');
        expect(preview).not.toBeNull();
    });

    test('queue item without previewUrl renders no Preview button', async () => {
        await flush();
        const es = MockEventSource.instances[0];
        es.fire('queueChanged', { guildId: 42, queue: [track({ sourceName: 'youtube', previewUrl: null })] });
        await flush();

        expect(document.querySelector('#queue-list .queue-item')).not.toBeNull();
        expect(document.querySelector('#queue-list .btn-preview')).toBeNull();
    });

    test('clicking Preview plays the audio and swaps the button to ⏸', async () => {
        await flush();
        const es = MockEventSource.instances[0];
        es.fire('queueChanged', { guildId: 42, queue: [track()] });
        await flush();

        const btn = document.querySelector('#queue-list .btn-preview');
        btn.click();
        await flush();

        expect(playMock).toHaveBeenCalledTimes(1);
        const audio = document.getElementById('preview-audio');
        expect(audio.src).toBe('https://p.scdn.co/mp3-preview/abc');
        expect(btn.textContent).toBe('⏸');
        expect(btn.getAttribute('aria-pressed')).toBe('true');
    });

    test('clicking the active Preview button again pauses', async () => {
        await flush();
        const es = MockEventSource.instances[0];
        es.fire('queueChanged', { guildId: 42, queue: [track()] });
        await flush();

        const btn = document.querySelector('#queue-list .btn-preview');
        btn.click();
        await flush();
        btn.click();
        await flush();

        expect(pauseMock).toHaveBeenCalledTimes(1);
        expect(btn.textContent).toBe('▶');
        expect(btn.getAttribute('aria-pressed')).toBe('false');
    });

    test('clicking a different Preview button stops the first and starts the second', async () => {
        await flush();
        const es = MockEventSource.instances[0];
        es.fire('queueChanged', {
            guildId: 42,
            queue: [
                track({ identifier: 'a', uri: 'https://example.com/a', previewUrl: 'https://p.scdn.co/preview/a' }),
                track({ identifier: 'b', uri: 'https://example.com/b', previewUrl: 'https://p.scdn.co/preview/b' }),
            ],
        });
        await flush();

        const btns = document.querySelectorAll('#queue-list .btn-preview');
        expect(btns.length).toBe(2);

        btns[0].click();
        await flush();
        expect(btns[0].textContent).toBe('⏸');
        expect(btns[1].textContent).toBe('▶');

        btns[1].click();
        await flush();
        // The first button reverted to ▶ (we stop-then-play on switch);
        // the second is now active.
        expect(btns[0].textContent).toBe('▶');
        expect(btns[1].textContent).toBe('⏸');
        const audio = document.getElementById('preview-audio');
        expect(audio.src).toBe('https://p.scdn.co/preview/b');
    });

    test('SSE queueChanged that removes the previewing track resets the controller', async () => {
        await flush();
        const es = MockEventSource.instances[0];
        es.fire('queueChanged', {
            guildId: 42,
            queue: [
                track({ identifier: 'a', uri: 'https://example.com/a', previewUrl: 'https://p.scdn.co/preview/a' }),
                track({ identifier: 'b', uri: 'https://example.com/b', previewUrl: 'https://p.scdn.co/preview/b' }),
            ],
        });
        await flush();

        const firstBtn = document.querySelectorAll('#queue-list .btn-preview')[0];
        firstBtn.click();
        await flush();
        expect(firstBtn.textContent).toBe('⏸');

        // Now the queue refreshes without track 'a' — the controller should
        // pause and forget about it.
        es.fire('queueChanged', {
            guildId: 42,
            queue: [track({ identifier: 'b', uri: 'https://example.com/b', previewUrl: 'https://p.scdn.co/preview/b' })],
        });
        await flush();

        expect(pauseMock).toHaveBeenCalled();
        // The only remaining button starts in the inactive state.
        const remaining = document.querySelectorAll('#queue-list .btn-preview');
        expect(remaining.length).toBe(1);
        expect(remaining[0].textContent).toBe('▶');
    });

    test('clearing search results stops a search-row preview but not a queue-row preview', async () => {
        await flush();
        const es = MockEventSource.instances[0];

        // Set up: one queue item + a search showing one result, both
        // previewable.
        es.fire('queueChanged', {
            guildId: 42,
            queue: [track({ identifier: 'q', uri: 'https://example.com/q', previewUrl: 'https://p.scdn.co/preview/q' })],
        });
        await flush();

        fetchMock.mockImplementationOnce(() => Promise.resolve({
            ok: true,
            json: () => Promise.resolve([track({ identifier: 's', uri: 'https://example.com/s', previewUrl: 'https://p.scdn.co/preview/s' })]),
        }));
        document.getElementById('add-track-input').value = 'linkin park';
        document.getElementById('add-track-form').dispatchEvent(new Event('submit', { cancelable: true }));
        await flush();

        // Start a preview on the search row.
        const searchBtn = document.querySelector('#search-results-list .btn-preview');
        searchBtn.click();
        await flush();
        expect(searchBtn.textContent).toBe('⏸');
        const pausesBefore = pauseMock.mock.calls.length;

        // Clear results — should stop the audio since the active button is
        // in the search list.
        document.getElementById('search-results-close').click();
        await flush();
        expect(pauseMock.mock.calls.length).toBeGreaterThan(pausesBefore);

        // Now flip the scenario: preview the queue row, then clear search.
        // Re-run a search just to populate the search list (no auto-clear).
        fetchMock.mockImplementationOnce(() => Promise.resolve({
            ok: true,
            json: () => Promise.resolve([track({ identifier: 's2', uri: 'https://example.com/s2', previewUrl: 'https://p.scdn.co/preview/s2' })]),
        }));
        document.getElementById('add-track-input').value = 'metric';
        document.getElementById('add-track-form').dispatchEvent(new Event('submit', { cancelable: true }));
        await flush();

        const queueBtn = document.querySelector('#queue-list .btn-preview');
        queueBtn.click();
        await flush();
        const pausesBefore2 = pauseMock.mock.calls.length;

        // Clearing search shouldn't touch the queue-row preview.
        document.getElementById('search-results-close').click();
        await flush();
        expect(pauseMock.mock.calls.length).toBe(pausesBefore2);
        expect(queueBtn.textContent).toBe('⏸');
    });

    test('audio "ended" event resets the active button', async () => {
        await flush();
        const es = MockEventSource.instances[0];
        es.fire('queueChanged', { guildId: 42, queue: [track()] });
        await flush();

        const btn = document.querySelector('#queue-list .btn-preview');
        btn.click();
        await flush();
        expect(btn.textContent).toBe('⏸');

        document.getElementById('preview-audio').dispatchEvent(new Event('ended'));
        await flush();
        expect(btn.textContent).toBe('▶');
        expect(btn.getAttribute('aria-pressed')).toBe('false');
    });
});
