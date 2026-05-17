// TobyBot music dashboard — wires the per-guild player UI to the
// /music-player REST endpoints and listens to the SSE stream for
// live updates from the Discord bot.
(function () {
    'use strict';

    const body = document.body;
    if (body.dataset.musicPage !== '1') return;

    const guildId = body.dataset.guildId;
    const baseUrl = '/music-player/' + guildId;

    const $ = (id) => document.getElementById(id);

    const els = {
        emptyState: $('now-playing-empty'),
        content: $('now-playing-content'),
        art: $('now-playing-art'),
        sourceBadge: $('source-badge'),
        title: $('now-playing-title'),
        author: $('now-playing-author'),
        requester: $('now-playing-requester'),
        requesterCell: $('now-playing-requester-cell'),
        timeCurrent: $('time-current'),
        timeTotal: $('time-total'),
        progressFill: $('progress-fill'),
        progressTrack: $('progress-track'),
        btnPause: $('btn-pause'),
        btnSkip: $('btn-skip'),
        btnStop: $('btn-stop'),
        btnLoop: $('btn-loop'),
        volumeSlider: $('volume-slider'),
        volumeValue: $('volume-value'),
        queueList: $('queue-list'),
        queueEmpty: $('queue-empty'),
        addForm: $('add-track-form'),
        addInput: $('add-track-input'),
        searchSection: $('search-results-section'),
        searchQueryLabel: $('search-results-query'),
        searchCloseBtn: $('search-results-close'),
        searchList: $('search-results-list'),
        searchEmpty: $('search-results-empty'),
        playlistList: $('playlist-list'),
        playlistsEmpty: $('playlists-empty'),
        savePlaylistForm: $('save-playlist-form'),
        savePlaylistName: $('save-playlist-name'),
        voiceChannelName: $('voice-channel-name'),
        voiceChannelEmpty: $('voice-channel-empty'),
        voiceMemberList: $('voice-member-list'),
    };

    let lastPaused = false;
    let lastDurationMs = 0;

    // ---- HTTP helpers --------------------------------------------------

    function csrfHeaders() {
        const headers = { 'Content-Type': 'application/json', 'Accept': 'application/json' };
        const tokenMeta = document.querySelector('meta[name="_csrf"]');
        const headerMeta = document.querySelector('meta[name="_csrf_header"]');
        if (tokenMeta && headerMeta) headers[headerMeta.content] = tokenMeta.content;
        return headers;
    }

    function getJson(path) {
        return fetch(baseUrl + path, { credentials: 'same-origin' })
            .then((r) => r.ok ? r.json() : Promise.reject(new Error('HTTP ' + r.status)));
    }

    function sendJson(method, path, body) {
        const init = { method: method, credentials: 'same-origin', headers: csrfHeaders() };
        if (body !== undefined) init.body = JSON.stringify(body);
        return fetch(baseUrl + path, init).then((r) =>
            r.json().catch(() => ({ ok: r.ok, message: r.ok ? null : 'Request failed' })),
        );
    }

    const post = (path, body) => sendJson('POST', path, body);
    const del = (path) => sendJson('DELETE', path);

    // ---- Rendering -----------------------------------------------------

    function formatMs(ms) {
        if (ms == null || ms < 0 || !isFinite(ms)) return '0:00';
        const total = Math.floor(ms / 1000);
        const m = Math.floor(total / 60);
        const s = total % 60;
        return m + ':' + (s < 10 ? '0' : '') + s;
    }

    const SOURCE_COLORS = {
        youtube: '#ff0000',
        spotify: '#1db954',
        soundcloud: '#ff5500',
        applemusic: '#fa243c',
        bandcamp: '#1da0c3',
        vimeo: '#1ab7ea',
        deezer: '#a238ff',
        yandexmusic: '#ffcc00',
        twitch: '#9146ff',
    };

    const SOURCE_LABELS = {
        youtube: 'YouTube',
        spotify: 'Spotify',
        soundcloud: 'SoundCloud',
        applemusic: 'Apple Music',
        bandcamp: 'Bandcamp',
        vimeo: 'Vimeo',
        deezer: 'Deezer',
        yandexmusic: 'Yandex Music',
        twitch: 'Twitch',
        http: 'Direct stream',
        local: 'Local file',
        nico: 'Niconico',
    };

    function renderRequester(track) {
        // Mirrors the duel / leaderboard / moderation `.member-cell` primitive
        // so a "Requested by" surface anywhere on the player matches the rest
        // of the site. Hidden entirely when no requester is known (intros,
        // playlists re-loaded by another user, etc.).
        const name = track && track.requesterDisplayName;
        const avatar = track && track.requesterAvatarUrl;
        const fallback = track && track.requesterDiscordId;
        if (!name && !fallback) {
            els.requester.hidden = true;
            els.requesterCell.innerHTML = '';
            return;
        }
        els.requester.hidden = false;
        els.requesterCell.innerHTML = '';
        if (avatar) {
            const img = document.createElement('img');
            img.className = 'avatar';
            img.src = avatar;
            img.alt = '';
            img.loading = 'lazy';
            els.requesterCell.appendChild(img);
        }
        const nameEl = document.createElement('span');
        nameEl.className = 'lb-name';
        // textContent — guards against display names like "<script>".
        nameEl.textContent = name || ('<@' + fallback + '>');
        els.requesterCell.appendChild(nameEl);
    }

    function renderNowPlaying(track, paused) {
        if (!track) {
            els.content.hidden = true;
            els.emptyState.hidden = false;
            els.title.textContent = '—';
            els.author.textContent = '—';
            els.requester.textContent = '';
            els.progressFill.style.width = '0%';
            els.timeCurrent.textContent = '0:00';
            els.timeTotal.textContent = '0:00';
            lastDurationMs = 0;
            return;
        }
        els.emptyState.hidden = true;
        els.content.hidden = false;
        els.title.textContent = track.title || '(untitled)';
        if (track.uri) {
            els.title.classList.add('linkable');
            els.title.onclick = () => window.open(track.uri, '_blank', 'noopener');
        } else {
            els.title.classList.remove('linkable');
            els.title.onclick = null;
        }
        els.author.textContent = track.author || '';
        renderRequester(track);
        if (track.artworkUrl) {
            els.art.src = track.artworkUrl;
            els.art.style.display = '';
        } else {
            els.art.removeAttribute('src');
            els.art.style.display = 'none';
        }
        const source = (track.sourceName || '').toLowerCase();
        const color = SOURCE_COLORS[source] || '#57f287';
        const label = SOURCE_LABELS[source] || 'Music';
        els.sourceBadge.textContent = label;
        els.sourceBadge.style.backgroundColor = color;
        lastDurationMs = track.durationMs || 0;
        els.timeTotal.textContent = track.isStream ? 'LIVE' : formatMs(lastDurationMs);
        lastPaused = !!paused;
        els.btnPause.textContent = paused ? '▶️' : '⏸️';
    }

    function renderProgress(positionMs) {
        if (lastDurationMs <= 0) {
            els.progressFill.style.width = '0%';
            els.timeCurrent.textContent = '0:00';
            return;
        }
        const pct = Math.max(0, Math.min(100, (positionMs / lastDurationMs) * 100));
        els.progressFill.style.width = pct + '%';
        els.timeCurrent.textContent = formatMs(positionMs);
    }

    function renderQueue(tracks) {
        els.queueList.innerHTML = '';
        if (!tracks || tracks.length === 0) {
            els.queueEmpty.hidden = false;
            return;
        }
        els.queueEmpty.hidden = true;
        tracks.forEach((track, i) => {
            const li = document.createElement('li');
            li.className = 'queue-item';
            li.draggable = true;
            li.dataset.index = String(i);

            const handle = document.createElement('span');
            handle.className = 'queue-drag-handle';
            handle.setAttribute('aria-hidden', 'true');
            handle.textContent = '⋮⋮';
            li.appendChild(handle);

            const meta = document.createElement('div');
            meta.className = 'queue-meta';
            const titleEl = document.createElement('div');
            titleEl.className = 'queue-title';
            titleEl.textContent = track.title || '(untitled)';
            const authorEl = document.createElement('div');
            authorEl.className = 'queue-author';
            authorEl.textContent = (track.author || '') + (track.durationMs ? ' • ' + formatMs(track.durationMs) : '');
            meta.appendChild(titleEl);
            meta.appendChild(authorEl);
            li.appendChild(meta);

            const removeBtn = document.createElement('button');
            removeBtn.className = 'queue-remove';
            removeBtn.type = 'button';
            removeBtn.setAttribute('aria-label', 'Remove track');
            removeBtn.textContent = '✕';
            removeBtn.addEventListener('click', () => {
                del('/queue/' + i).then(() => {
                    // SSE queueChanged will refresh the UI authoritatively.
                });
            });
            li.appendChild(removeBtn);

            els.queueList.appendChild(li);
        });

        if (window.TobyMusicQueue && typeof window.TobyMusicQueue.attach === 'function') {
            window.TobyMusicQueue.attach(els.queueList, (from, to) => post('/queue/reorder', { from: from, to: to }));
        }
    }

    function renderPlaylists(playlists) {
        els.playlistList.innerHTML = '';
        if (!playlists || playlists.length === 0) {
            els.playlistsEmpty.hidden = false;
            return;
        }
        els.playlistsEmpty.hidden = true;
        playlists.forEach((pl) => {
            const li = document.createElement('li');
            li.className = 'playlist-item';

            const meta = document.createElement('div');
            meta.className = 'playlist-meta';
            const name = document.createElement('div');
            name.className = 'playlist-name';
            name.textContent = pl.name;
            const count = document.createElement('div');
            count.className = 'playlist-count muted';
            count.textContent = pl.trackCount + ' track' + (pl.trackCount === 1 ? '' : 's');
            meta.appendChild(name);
            meta.appendChild(count);
            li.appendChild(meta);

            const actions = document.createElement('div');
            actions.className = 'playlist-actions';
            const loadBtn = document.createElement('button');
            loadBtn.type = 'button';
            loadBtn.className = 'btn-tertiary';
            loadBtn.textContent = '⏵ Load';
            loadBtn.addEventListener('click', () => {
                post('/playlists/' + pl.id + '/load');
            });
            actions.appendChild(loadBtn);

            const delBtn = document.createElement('button');
            delBtn.type = 'button';
            delBtn.className = 'btn-tertiary btn-danger';
            delBtn.textContent = '🗑 Delete';
            delBtn.addEventListener('click', () => {
                if (!confirm('Delete playlist "' + pl.name + '"?')) return;
                del('/playlists/' + pl.id).then(() => refreshPlaylists());
            });
            actions.appendChild(delBtn);

            li.appendChild(actions);
            els.playlistList.appendChild(li);
        });
    }

    function refreshPlaylists() {
        getJson('/playlists').then(renderPlaylists).catch(() => {});
    }

    function renderVoiceChannel(voice) {
        if (!voice) {
            els.voiceChannelName.hidden = true;
            els.voiceChannelEmpty.hidden = false;
            els.voiceMemberList.innerHTML = '';
            return;
        }
        els.voiceChannelEmpty.hidden = true;
        els.voiceChannelName.hidden = false;
        els.voiceChannelName.textContent = '#' + voice.name;
        els.voiceMemberList.innerHTML = '';
        (voice.members || []).forEach((m) => {
            const li = document.createElement('li');
            li.className = 'voice-member';
            if (m.isBot) li.classList.add('is-bot');

            const img = document.createElement('img');
            img.className = 'voice-member-avatar';
            img.alt = '';
            if (m.avatarUrl) img.src = m.avatarUrl;
            li.appendChild(img);

            const name = document.createElement('span');
            name.className = 'voice-member-name';
            name.textContent = m.displayName + (m.isBot ? ' (bot)' : '');
            li.appendChild(name);

            if (m.isDeafened) {
                const ic = document.createElement('span');
                ic.className = 'voice-member-deaf';
                ic.title = 'Deafened';
                ic.setAttribute('aria-label', 'Deafened');
                ic.textContent = '🔇';
                li.appendChild(ic);
            } else if (m.isMuted) {
                const ic = document.createElement('span');
                ic.className = 'voice-member-mute';
                ic.title = 'Muted';
                ic.setAttribute('aria-label', 'Muted');
                ic.textContent = '🎙️❌';
                li.appendChild(ic);
            }
            els.voiceMemberList.appendChild(li);
        });
    }

    function refreshState() {
        getJson('/state').then((envelope) => {
            const state = envelope && envelope.state;
            if (!state) return;
            renderNowPlaying(state.nowPlaying, state.paused);
            renderQueue(state.queue);
            renderProgress(state.positionMs || 0);
            renderVoiceChannel(state.voiceChannel);
            els.volumeSlider.value = String(state.volume);
            els.volumeValue.textContent = String(state.volume);
            els.btnLoop.setAttribute('aria-pressed', state.looping ? 'true' : 'false');
            els.btnLoop.classList.toggle('active', !!state.looping);
        }).catch(() => {});
    }

    // ---- Transport handlers --------------------------------------------

    els.btnPause.addEventListener('click', () => {
        post(lastPaused ? '/resume' : '/pause');
    });
    els.btnSkip.addEventListener('click', () => post('/skip', { count: 1 }));
    els.btnStop.addEventListener('click', () => post('/stop'));
    els.btnLoop.addEventListener('click', () => {
        const next = els.btnLoop.getAttribute('aria-pressed') !== 'true';
        post('/loop', { looping: next });
    });
    let volumeTimer = null;
    els.volumeSlider.addEventListener('input', () => {
        els.volumeValue.textContent = els.volumeSlider.value;
        if (volumeTimer) clearTimeout(volumeTimer);
        volumeTimer = setTimeout(() => post('/volume', { volume: Number(els.volumeSlider.value) }), 200);
    });

    els.progressTrack.addEventListener('click', (e) => {
        if (lastDurationMs <= 0) return;
        const rect = els.progressTrack.getBoundingClientRect();
        const ratio = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
        post('/seek', { positionMs: Math.floor(ratio * lastDurationMs) });
    });

    // Heuristic: if the user pasted a URL we trust them and queue it
    // directly; if they typed free text we GET /search so they can see what
    // would actually play before committing.
    function looksLikeUrl(s) {
        return /^https?:\/\//i.test(s);
    }

    function clearSearchResults() {
        els.searchSection.hidden = true;
        els.searchList.innerHTML = '';
        els.searchEmpty.hidden = true;
        els.searchQueryLabel.textContent = '';
    }

    function renderSearchResults(query, results) {
        els.searchSection.hidden = false;
        els.searchQueryLabel.textContent = '"' + query + '"';
        els.searchList.innerHTML = '';
        if (!results || results.length === 0) {
            els.searchEmpty.hidden = false;
            return;
        }
        els.searchEmpty.hidden = true;
        results.forEach((track) => {
            const li = document.createElement('li');
            li.className = 'search-result';

            const meta = document.createElement('div');
            meta.className = 'search-result-meta';
            const title = document.createElement('div');
            title.className = 'search-result-title';
            title.textContent = track.title || '(untitled)';
            const author = document.createElement('div');
            author.className = 'search-result-author';
            const dur = track.durationMs ? ' • ' + formatMs(track.durationMs) : '';
            const src = track.sourceName ? ' • ' + (SOURCE_LABELS[track.sourceName.toLowerCase()] || track.sourceName) : '';
            author.textContent = (track.author || '') + dur + src;
            meta.appendChild(title);
            meta.appendChild(author);
            li.appendChild(meta);

            const queueBtn = document.createElement('button');
            queueBtn.type = 'button';
            queueBtn.className = 'btn-primary';
            queueBtn.textContent = 'Queue';
            queueBtn.addEventListener('click', () => {
                queueBtn.disabled = true;
                queueBtn.textContent = 'Queueing…';
                post('/load', { query: track.uri || track.identifier }).then((res) => {
                    if (res && res.ok) {
                        clearSearchResults();
                        els.addInput.value = '';
                    } else {
                        queueBtn.disabled = false;
                        queueBtn.textContent = 'Queue';
                        if (res && res.message) alert(res.message);
                    }
                });
            });
            li.appendChild(queueBtn);
            els.searchList.appendChild(li);
        });
    }

    els.addForm.addEventListener('submit', (e) => {
        e.preventDefault();
        const q = els.addInput.value.trim();
        if (!q) return;

        if (looksLikeUrl(q)) {
            // Pasted URLs go straight to /load — no preview needed.
            post('/load', { query: q }).then((res) => {
                if (res && res.ok) {
                    els.addInput.value = '';
                    clearSearchResults();
                } else if (res && res.message) {
                    alert(res.message);
                }
            });
            return;
        }

        // Free-text query — preview results, let the user pick.
        fetch(baseUrl + '/search?q=' + encodeURIComponent(q), { credentials: 'same-origin' })
            .then((r) => r.ok ? r.json() : [])
            .then((results) => renderSearchResults(q, results))
            .catch(() => renderSearchResults(q, []));
    });

    els.searchCloseBtn.addEventListener('click', clearSearchResults);

    els.savePlaylistForm.addEventListener('submit', (e) => {
        e.preventDefault();
        const name = els.savePlaylistName.value.trim();
        if (!name) return;
        post('/playlists', { name: name }).then((res) => {
            if (res && res.ok) {
                els.savePlaylistName.value = '';
                refreshPlaylists();
            } else if (res && res.message) {
                alert(res.message);
            }
        });
    });

    // ---- SSE -----------------------------------------------------------

    let eventSource = null;

    function connectSse() {
        if (eventSource) eventSource.close();
        eventSource = new EventSource(baseUrl + '/events');

        eventSource.addEventListener('trackStart', (ev) => {
            const payload = JSON.parse(ev.data);
            renderNowPlaying(payload.track, false);
        });
        eventSource.addEventListener('trackEnd', () => {
            // When the last track in the queue ends (or the user hits stop)
            // no follow-up trackStart fires — so without this the now-playing
            // card would keep showing the just-ended track. /state is
            // authoritative: it returns nowPlaying=null when nothing is
            // playing, which clears the card. If another track was queued,
            // the trackStart event that fires alongside will override the
            // brief gap.
            refreshState();
        });
        eventSource.addEventListener('queueChanged', (ev) => {
            const payload = JSON.parse(ev.data);
            renderQueue(payload.queue);
        });
        eventSource.addEventListener('pauseStateChanged', (ev) => {
            const payload = JSON.parse(ev.data);
            lastPaused = !!payload.paused;
            els.btnPause.textContent = lastPaused ? '▶️' : '⏸️';
        });
        eventSource.addEventListener('volumeChanged', (ev) => {
            const payload = JSON.parse(ev.data);
            els.volumeSlider.value = String(payload.volume);
            els.volumeValue.textContent = String(payload.volume);
        });
        eventSource.addEventListener('loopStateChanged', (ev) => {
            const payload = JSON.parse(ev.data);
            els.btnLoop.setAttribute('aria-pressed', payload.looping ? 'true' : 'false');
            els.btnLoop.classList.toggle('active', !!payload.looping);
        });
        eventSource.addEventListener('positionTick', (ev) => {
            const payload = JSON.parse(ev.data);
            if (payload.durationMs > 0) lastDurationMs = payload.durationMs;
            renderProgress(payload.positionMs);
        });
        eventSource.onerror = () => {
            // EventSource will reconnect automatically. Re-sync state on reconnect.
            setTimeout(() => refreshState(), 2000);
        };
    }

    // Backgrounded tabs commonly have their EventSource throttled or
    // silently closed by the OS / a proxy. When the tab comes back into
    // focus, re-pull state immediately AND reopen the SSE channel so we
    // don't sit on a dead socket showing a stale progress bar.
    document.addEventListener('visibilitychange', () => {
        if (document.visibilityState !== 'visible') return;
        refreshState();
        refreshPlaylists();
        connectSse();
    });

    // ---- Bootstrap -----------------------------------------------------

    refreshState();
    refreshPlaylists();
    connectSse();
})();
