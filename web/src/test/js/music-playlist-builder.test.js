// Verifies the "build playlists without playing" promise on the client:
// adding a search result to a playlist POSTs the resolved metadata to the
// playlist-items endpoint and NEVER hits /load (which would queue/play it).

class MockEventSource {
    constructor(url) {
        this.url = url;
        this.handlers = {};
        MockEventSource.instances.push(this);
    }
    addEventListener(name, handler) { this.handlers[name] = handler; }
    close() {}
    fire(name, payload) {
        const handler = this.handlers[name];
        if (handler) handler({ data: JSON.stringify(payload) });
    }
}
MockEventSource.instances = [];

function flush() {
    return new Promise((resolve) => setTimeout(resolve, 0));
}

function json(body) {
    return Promise.resolve({ ok: true, json: () => Promise.resolve(body) });
}

function idleState() {
    return {
        guildId: 42, nowPlaying: null, positionMs: 0, paused: false,
        volume: 100, looping: false, queue: [], voiceChannelId: null, voiceChannel: null,
    };
}

function track() {
    return {
        identifier: 'id-1', title: 'Numb', author: 'Linkin Park',
        durationMs: 185000, uri: 'https://youtu.be/x', previewUrl: null, sourceName: 'youtube',
    };
}

function setUpDashboardDom() {
    document.head.innerHTML = '';
    document.body.innerHTML = `
        <div id="now-playing-empty"></div>
        <div id="now-playing-content" hidden>
            <span id="source-badge"></span><h3 id="now-playing-title"></h3>
            <p id="now-playing-author"></p>
            <div id="now-playing-requester" hidden><span id="now-playing-requester-cell"></span></div>
            <span id="time-current"></span><span id="time-total"></span>
            <div id="progress-track"><div id="progress-fill"></div></div>
        </div>
        <button id="btn-pause"></button><button id="btn-skip"></button>
        <button id="btn-stop"></button><button id="btn-loop" aria-pressed="false"></button>
        <input id="volume-slider" type="range" min="0" max="150" value="100" />
        <span id="volume-value">100</span>
        <form id="add-track-form"><input id="add-track-input" /><button>Search</button></form>
        <section id="search-results-section" hidden>
            <span id="search-results-query"></span>
            <button id="search-results-close"></button>
            <ol id="search-results-list"></ol>
            <p id="search-results-empty" hidden></p>
        </section>
        <ol id="queue-list"></ol><p id="queue-empty"></p>
        <ul id="playlist-list"></ul><p id="playlists-empty"></p>
        <form id="save-playlist-form">
            <input id="save-playlist-name" />
            <div class="save-playlist-actions">
                <button type="submit">＋ New</button>
                <button type="button" id="save-queue-btn">Save queue</button>
            </div>
        </form>
        <p id="voice-channel-name" hidden></p><p id="voice-channel-empty"></p>
        <ul id="voice-member-list"></ul>
    `;
    document.body.dataset.musicPage = '1';
    document.body.dataset.guildId = '42';
    document.body.dataset.discordId = '100';
}

describe('music-player.js — build playlists without playing', () => {
    let fetchMock;
    let visibilitySpy;

    beforeEach(() => {
        jest.resetModules();
        MockEventSource.instances.length = 0;
        setUpDashboardDom();
        window.EventSource = MockEventSource;

        fetchMock = jest.fn().mockImplementation((url) => {
            if (url.endsWith('/state')) return json({ state: idleState() });
            if (url.endsWith('/playlists')) return json([]);
            return json({});
        });
        window.fetch = fetchMock;
        visibilitySpy = jest.spyOn(document, 'visibilityState', 'get');
        visibilitySpy.mockReturnValue('visible');

        require('../../main/resources/static/js/music-player');
    });

    afterEach(() => visibilitySpy.mockRestore());

    function methodRouter(url, init) {
        const method = (init && init.method) || 'GET';
        if (url.endsWith('/state')) return json({ state: idleState() });
        if (url.includes('/search')) return json([track()]);
        if (url.endsWith('/playlists') && method === 'GET') return json([]);
        if (url.endsWith('/playlists') && method === 'POST') return json({ ok: true, id: 5 });
        if (url.endsWith('/playlists/5/items') && method === 'POST') {
            return json({ ok: true, detail: { id: 5, name: 'My Mix', ownerDiscordId: 100, canEdit: true, items: [] } });
        }
        return json({});
    }

    test('adding a search hit to a new playlist posts items and never calls /load', async () => {
        await flush();
        fetchMock.mockImplementation(methodRouter);

        // Run a free-text search so the results panel renders.
        document.getElementById('add-track-input').value = 'numb';
        document.getElementById('add-track-form').dispatchEvent(new Event('submit', { cancelable: true }));
        await flush();

        const addBtn = document.querySelector('#search-results-list .btn-add-playlist');
        expect(addBtn).not.toBeNull();

        addBtn.click();
        const menu = document.querySelector('.add-to-playlist-menu');
        expect(menu.hidden).toBe(false);

        // Create-and-add via the inline "new playlist" form in the menu.
        menu.querySelector('.add-to-playlist-new-input').value = 'My Mix';
        menu.querySelector('.add-to-playlist-new').dispatchEvent(new Event('submit', { cancelable: true }));
        await flush();
        await flush();

        const calls = fetchMock.mock.calls;
        const createCall = calls.find(([u, i]) => u.endsWith('/playlists') && i && i.method === 'POST');
        expect(createCall).toBeTruthy();
        expect(JSON.parse(createCall[1].body).fromQueue).toBe(false);

        const addCall = calls.find(([u, i]) => u.endsWith('/playlists/5/items') && i && i.method === 'POST');
        expect(addCall).toBeTruthy();
        expect(JSON.parse(addCall[1].body).identifier).toBe('https://youtu.be/x');

        // The whole point: curating never queues/plays the track.
        expect(calls.some(([u]) => u.endsWith('/load'))).toBe(false);
    });
});
