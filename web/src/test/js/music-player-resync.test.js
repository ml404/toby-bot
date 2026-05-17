// Regression tests for two bugs in the music dashboard:
//
//  1. "trackEnd doesn't clear the now-playing card" — when the last
//     queued track ended (or the user hit Stop), only a TrackEndedEvent
//     fired; no follow-up TrackStartedEvent ever arrived, so the
//     now-playing UI kept showing the just-ended track until refresh.
//     Fix: the trackEnd handler now refetches /state, which is
//     authoritative (returns nowPlaying=null when idle).
//
//  2. "Tab background → progress bar freezes" — browsers throttle
//     EventSource on backgrounded tabs and proxies can silently drop
//     the connection. Fix: visibilitychange→visible resyncs state,
//     playlists, AND reopens the SSE channel.
//
// We boot the music-player.js IIFE against a hand-crafted DOM, stub
// fetch + EventSource, and fire DOM/SSE events to prove the handlers
// behave as advertised.

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
    close() {
        this.closed = true;
    }
    // Fire a named SSE event with a JSON-encoded payload, matching how
    // EventSource hands events to addEventListener-style listeners.
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
    `;
    document.body.dataset.musicPage = '1';
    document.body.dataset.guildId = '42';
    document.body.dataset.discordId = '100';
}

describe('music-player.js — live state resync', () => {
    let fetchMock;
    let visibilitySpy;

    beforeEach(() => {
        jest.resetModules();
        MockEventSource.instances.length = 0;

        setUpDashboardDom();

        // EventSource isn't in jsdom; install our recording mock.
        window.EventSource = MockEventSource;

        // Default fetch handler: empty state, empty playlist list, empty
        // search results. Tests override via mockResolvedValueOnce.
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

        // visibilityState is a getter — stub it so each test can flip it.
        visibilitySpy = jest.spyOn(document, 'visibilityState', 'get');
        visibilitySpy.mockReturnValue('visible');

        require('../../main/resources/static/js/music-player');
    });

    afterEach(() => {
        visibilitySpy.mockRestore();
    });

    test('boots: opens an EventSource and fetches initial /state + /playlists', async () => {
        await flush();
        expect(MockEventSource.instances).toHaveLength(1);
        const stateCalls = fetchMock.mock.calls.filter(([u]) => u.endsWith('/state'));
        const playlistCalls = fetchMock.mock.calls.filter(([u]) => u.endsWith('/playlists'));
        expect(stateCalls.length).toBeGreaterThanOrEqual(1);
        expect(playlistCalls.length).toBeGreaterThanOrEqual(1);
    });

    test('SSE trackEnd refetches /state so the now-playing card clears when nothing follows', async () => {
        await flush();
        const initialStateCalls = fetchMock.mock.calls.filter(([u]) => u.endsWith('/state')).length;
        const es = MockEventSource.instances[0];

        // The frontend should always handle a registered trackEnd event.
        expect(typeof es.handlers.trackEnd).toBe('function');

        // Fire it — the handler should re-fetch /state. We don't care about
        // the SSE payload contents (trackEnd is not data-bearing in our flow).
        es.fire('trackEnd', { guildId: 42, endReason: 'FINISHED' });
        await flush();

        const newStateCalls = fetchMock.mock.calls.filter(([u]) => u.endsWith('/state')).length;
        expect(newStateCalls).toBeGreaterThan(initialStateCalls);
    });

    test('trackEnd renders the cleared state — empty card shows, content hides', async () => {
        await flush();
        const es = MockEventSource.instances[0];

        // First, simulate that something was playing — fire a trackStart so
        // the now-playing card has content to clear.
        es.fire('trackStart', { guildId: 42, track: playingTrack() });
        await flush();
        expect(document.getElementById('now-playing-content').hidden).toBe(false);

        // Now arrange /state to return idle (last track ended, queue empty).
        fetchMock.mockImplementationOnce((url) => {
            if (url.endsWith('/state')) {
                return Promise.resolve({
                    ok: true,
                    json: () => Promise.resolve({ state: idleState() }),
                });
            }
            return Promise.resolve({ ok: true, json: () => Promise.resolve({}) });
        });

        es.fire('trackEnd', { guildId: 42, endReason: 'FINISHED' });
        await flush();

        // The empty state should be visible, the content hidden.
        expect(document.getElementById('now-playing-empty').hidden).toBe(false);
        expect(document.getElementById('now-playing-content').hidden).toBe(true);
    });

    test('visibilitychange→visible reopens the EventSource and resyncs state', async () => {
        await flush();
        // Note on isolation: music-player.js attaches its visibilitychange
        // listener to `document`, which survives jest.resetModules() between
        // tests. So earlier require()s from prior tests can leave orphan
        // listeners that also fire here. We assert on the *behaviour* of
        // this run's listener (old socket closed, at least one new instance
        // opened, state and playlists refetched) rather than the absolute
        // count of new instances.
        const previousInstance = MockEventSource.instances[MockEventSource.instances.length - 1];
        const beforeInstances = MockEventSource.instances.length;
        const beforeStateCalls = fetchMock.mock.calls.filter(([u]) => u.endsWith('/state')).length;
        const beforePlaylistCalls = fetchMock.mock.calls.filter(([u]) => u.endsWith('/playlists')).length;

        visibilitySpy.mockReturnValue('visible');
        document.dispatchEvent(new Event('visibilitychange'));
        await flush();

        // The pre-event socket was closed (proves the handler ran the
        // close-then-reopen cycle, not "leave the dead socket alone").
        expect(previousInstance.closed).toBe(true);
        // A new EventSource was constructed in its place.
        expect(MockEventSource.instances.length).toBeGreaterThan(beforeInstances);
        // State and playlists were re-fetched.
        const afterStateCalls = fetchMock.mock.calls.filter(([u]) => u.endsWith('/state')).length;
        const afterPlaylistCalls = fetchMock.mock.calls.filter(([u]) => u.endsWith('/playlists')).length;
        expect(afterStateCalls).toBeGreaterThan(beforeStateCalls);
        expect(afterPlaylistCalls).toBeGreaterThan(beforePlaylistCalls);
    });

    test('visibilitychange→hidden does not reopen the EventSource', async () => {
        await flush();
        const beforeInstances = MockEventSource.instances.length;

        visibilitySpy.mockReturnValue('hidden');
        document.dispatchEvent(new Event('visibilitychange'));
        await flush();

        expect(MockEventSource.instances.length).toBe(beforeInstances);
    });
});

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

function playingTrack() {
    return {
        identifier: 'abc',
        title: 'Test Track',
        author: 'Author',
        durationMs: 60_000,
        uri: 'https://example.com',
        artworkUrl: null,
        sourceName: 'youtube',
        isStream: false,
        requesterDiscordId: 100,
        requesterDisplayName: 'Alice',
        requesterAvatarUrl: 'https://cdn/a.png',
    };
}
