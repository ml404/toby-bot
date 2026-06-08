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
        card: $('now-playing-card'),
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
        saveQueueBtn: $('save-queue-btn'),
        voiceChannelName: $('voice-channel-name'),
        voiceChannelEmpty: $('voice-channel-empty'),
        voiceMemberList: $('voice-member-list'),
    };

    // Current user — lets us show edit affordances only on playlists they own.
    const myDiscordId = body.dataset.discordId || '';
    // Cache of the last-rendered playlist summaries + which one is expanded in
    // the inline editor, so SSE-less refreshes (save / delete / visibility)
    // can re-open the same editor without the user losing their place.
    let playlistsCache = [];
    let expandedPlaylistId = null;

    let lastPaused = false;
    let lastDurationMs = 0;

    // Toggles the accent glow + bouncing equalizer on the now-playing
    // hero card. Active only when a track is loaded AND not paused, so the
    // flourish always mirrors what the listener is actually hearing.
    function setPlayingFlourish(active) {
        if (els.card) els.card.classList.toggle('is-playing', !!active);
    }

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

    // Feedback through the shared toast primitive (toasts.js is loaded
    // globally) so the player matches the rest of the site instead of
    // firing OS-native alert() dialogs. No-ops if the message is empty.
    function toast(message, type) {
        if (message && window.TobyToasts) window.TobyToasts.show(message, { type: type || 'info' });
    }

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
            setPlayingFlourish(false);
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
        setPlayingFlourish(!paused);
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

    // ---- Preview --------------------------------------------------------
    // One shared <audio> drives every preview button (search results AND
    // queue items). Clicking a button starts/resumes that track's preview;
    // clicking it again pauses; clicking a different button stops the
    // current preview and starts the new one. Reaching the end of the
    // ~30s clip resets the button automatically. Button glyph always
    // mirrors the audio element's state (`play` / `pause` / `ended` events).
    const previewController = (() => {
        const audio = document.getElementById('preview-audio');
        let activeBtn = null;
        let activeKey = null;

        function setBtnPlaying(btn, playing) {
            btn.textContent = playing ? '⏸' : '▶';
            btn.setAttribute('aria-pressed', playing ? 'true' : 'false');
            btn.setAttribute('aria-label', playing ? 'Pause preview' : 'Play preview');
        }

        function reset() {
            // Stop the audio too — every caller of reset() wants playback
            // halted (search results being wiped, the previewing track
            // being removed from the queue, audio finishing / erroring).
            if (audio && !audio.paused) audio.pause();
            if (activeBtn) setBtnPlaying(activeBtn, false);
            activeBtn = null;
            activeKey = null;
        }

        if (audio) {
            audio.addEventListener('play', () => { if (activeBtn) setBtnPlaying(activeBtn, true); });
            audio.addEventListener('pause', () => { if (activeBtn) setBtnPlaying(activeBtn, false); });
            audio.addEventListener('ended', reset);
            audio.addEventListener('error', reset);
        }

        function trackKey(track) {
            return track.uri || track.identifier;
        }

        function toggle(track, btn) {
            if (!audio || !track || !track.previewUrl) return;
            if (activeBtn === btn) {
                if (audio.paused) audio.play().catch(reset);
                else audio.pause();
                return;
            }
            if (activeBtn) setBtnPlaying(activeBtn, false);
            audio.src = track.previewUrl;
            activeBtn = btn;
            activeKey = trackKey(track);
            setBtnPlaying(btn, true);
            audio.play().catch(reset);
        }

        /**
         * Called by renderQueue after an SSE queueChanged that removes
         * tracks. If the currently-previewing track is no longer in the
         * fresh queue, stop the audio and reset the (now-orphan) button.
         * Search-results rerenders go through clearSearchResults which
         * doesn't need this — the button list is wiped wholesale, and the
         * audio element keeps its src until reused.
         */
        function clearIfRemoved(remainingKeys) {
            if (!activeKey || !audio) return;
            if (remainingKeys.indexOf(activeKey) === -1) {
                audio.pause();
                reset();
            }
        }

        /**
         * True iff the currently-active preview button is a descendant of
         * [container]. Used by clearSearchResults to decide whether wiping
         * the results panel should also stop the preview — wipes don't
         * affect a queue-item preview that's playing.
         */
        function isActiveIn(container) {
            return !!(activeBtn && container && container.contains(activeBtn));
        }

        return {
            toggle: toggle,
            clearIfRemoved: clearIfRemoved,
            isActiveIn: isActiveIn,
            reset: reset,
        };
    })();

    function makePreviewBtn(track) {
        if (!track || !track.previewUrl) return null;
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'btn-preview';
        btn.setAttribute('aria-label', 'Play preview');
        btn.setAttribute('aria-pressed', 'false');
        btn.title = 'Preview ~30s clip';
        btn.textContent = '▶';
        btn.addEventListener('click', () => previewController.toggle(track, btn));
        return btn;
    }

    function renderQueue(tracks) {
        els.queueList.innerHTML = '';
        if (!tracks || tracks.length === 0) {
            els.queueEmpty.hidden = false;
            previewController.clearIfRemoved([]);
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

            const previewBtn = makePreviewBtn(track);
            if (previewBtn) li.appendChild(previewBtn);

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

        // After every queue re-render, drop the preview if its track is gone.
        previewController.clearIfRemoved(tracks.map((t) => t.uri || t.identifier));
    }

    // Small DOM helper to keep the playlist-editor builders readable.
    function makeEl(tag, className, text) {
        const el = document.createElement(tag);
        if (className) el.className = className;
        if (text != null) el.textContent = text;
        return el;
    }

    function ownsPlaylist(pl) {
        return myDiscordId !== '' && String(pl.ownerDiscordId) === myDiscordId;
    }

    function trackCountLabel(n) {
        return n + ' track' + (n === 1 ? '' : 's');
    }

    function renderPlaylists(playlists) {
        playlistsCache = playlists || [];
        els.playlistList.innerHTML = '';
        if (playlistsCache.length === 0) {
            els.playlistsEmpty.hidden = false;
            return;
        }
        els.playlistsEmpty.hidden = true;
        playlistsCache.forEach((pl) => {
            const li = makeEl('li', 'playlist-item');
            li.dataset.playlistId = String(pl.id);
            const owned = ownsPlaylist(pl);

            // Header row: expand toggle (name + count) then Load / Delete.
            const row = makeEl('div', 'playlist-row');

            const toggle = makeEl('button', 'playlist-toggle');
            toggle.type = 'button';
            toggle.setAttribute('aria-expanded', 'false');
            const caret = makeEl('span', 'playlist-caret', '▸');
            caret.setAttribute('aria-hidden', 'true');
            const name = makeEl('span', 'playlist-name', pl.name);
            const count = makeEl('span', 'playlist-count muted', trackCountLabel(pl.trackCount));
            toggle.appendChild(caret);
            toggle.appendChild(name);
            toggle.appendChild(count);
            row.appendChild(toggle);

            const actions = makeEl('div', 'playlist-actions');
            const loadBtn = makeEl('button', 'btn-tertiary', '⏵ Load');
            loadBtn.type = 'button';
            loadBtn.title = 'Add this playlist to the queue';
            loadBtn.addEventListener('click', () => {
                loadBtn.disabled = true;
                post('/playlists/' + pl.id + '/load').then((res) => {
                    loadBtn.disabled = false;
                    if (res && res.ok) toast(res.message || 'Playlist loaded.', 'success');
                    else toast((res && res.message) || 'Could not load playlist.', 'error');
                });
            });
            actions.appendChild(loadBtn);

            if (owned) {
                const delBtn = makeEl('button', 'btn-tertiary btn-danger', '🗑');
                delBtn.type = 'button';
                delBtn.title = 'Delete playlist';
                delBtn.setAttribute('aria-label', 'Delete playlist');
                delBtn.addEventListener('click', () => deletePlaylist(pl));
                actions.appendChild(delBtn);
            }
            row.appendChild(actions);
            li.appendChild(row);

            const panel = makeEl('div', 'playlist-editor');
            panel.hidden = true;
            li.appendChild(panel);

            toggle.addEventListener('click', () => togglePlaylist(pl, toggle, panel));

            els.playlistList.appendChild(li);

            // Re-open the editor that was open before this re-render.
            if (String(pl.id) === String(expandedPlaylistId)) {
                openPlaylist(pl, toggle, panel);
            }
        });
    }

    function togglePlaylist(pl, toggle, panel) {
        if (toggle.getAttribute('aria-expanded') === 'true') {
            expandedPlaylistId = null;
            toggle.setAttribute('aria-expanded', 'false');
            toggle.querySelector('.playlist-caret').textContent = '▸';
            panel.hidden = true;
        } else {
            openPlaylist(pl, toggle, panel);
        }
    }

    function openPlaylist(pl, toggle, panel) {
        expandedPlaylistId = pl.id;
        toggle.setAttribute('aria-expanded', 'true');
        toggle.querySelector('.playlist-caret').textContent = '▾';
        panel.hidden = false;
        panel.innerHTML = '<p class="muted pl-loading">Loading…</p>';
        getJson('/playlists/' + pl.id)
            .then((res) => {
                if (res && res.ok && res.detail) renderPlaylistEditor(panel, res.detail);
                else panel.innerHTML = '<p class="muted">Could not load this playlist.</p>';
            })
            .catch(() => { panel.innerHTML = '<p class="muted">Could not load this playlist.</p>'; });
    }

    function renderPlaylistEditor(panel, detail) {
        panel.innerHTML = '';

        if (detail.canEdit) {
            const renameForm = makeEl('form', 'playlist-rename-form');
            renameForm.autocomplete = 'off';
            const renameInput = makeEl('input', 'playlist-rename-input');
            renameInput.type = 'text';
            renameInput.maxLength = 80;
            renameInput.value = detail.name;
            renameInput.setAttribute('aria-label', 'Playlist name');
            const renameBtn = makeEl('button', 'btn-tertiary', 'Rename');
            renameBtn.type = 'submit';
            renameForm.appendChild(renameInput);
            renameForm.appendChild(renameBtn);
            renameForm.addEventListener('submit', (e) => {
                e.preventDefault();
                const newName = renameInput.value.trim();
                if (!newName || newName === detail.name) return;
                post('/playlists/' + detail.id + '/rename', { name: newName }).then((res) => {
                    if (res && res.ok && res.detail) {
                        toast('Playlist renamed.', 'success');
                        renderPlaylistEditor(panel, res.detail);
                        refreshPlaylists();
                    } else {
                        toast((res && res.message) || 'Could not rename playlist.', 'error');
                    }
                });
            });
            panel.appendChild(renameForm);
        }

        if (!detail.items || detail.items.length === 0) {
            panel.appendChild(makeEl('p', 'muted pl-empty',
                detail.canEdit
                    ? 'No tracks yet — find tracks in search and use “＋ Playlist”.'
                    : 'This playlist is empty.'));
            return;
        }

        const list = makeEl('ol', 'pl-track-list');
        detail.items.forEach((track, i) => {
            const item = makeEl('li', 'pl-track');
            item.dataset.index = String(i);
            if (detail.canEdit) {
                item.draggable = true;
                const handle = makeEl('span', 'pl-track-handle', '⋮⋮');
                handle.setAttribute('aria-hidden', 'true');
                item.appendChild(handle);
            }
            const meta = makeEl('div', 'pl-track-meta');
            meta.appendChild(makeEl('div', 'pl-track-title', track.title || '(untitled)'));
            const sub = [track.author || '', track.durationMs ? formatMs(track.durationMs) : '']
                .filter(Boolean).join(' • ');
            meta.appendChild(makeEl('div', 'pl-track-sub muted', sub));
            item.appendChild(meta);

            if (detail.canEdit) {
                const remove = makeEl('button', 'pl-track-remove', '✕');
                remove.type = 'button';
                remove.setAttribute('aria-label', 'Remove track');
                remove.addEventListener('click', () => {
                    del('/playlists/' + detail.id + '/items/' + track.id).then((res) => {
                        if (res && res.ok && res.detail) {
                            renderPlaylistEditor(panel, res.detail);
                            refreshPlaylists();
                        } else {
                            toast((res && res.message) || 'Could not remove track.', 'error');
                        }
                    });
                });
                item.appendChild(remove);
            }
            list.appendChild(item);
        });
        panel.appendChild(list);

        if (detail.canEdit && window.TobyMusicQueue && typeof window.TobyMusicQueue.attach === 'function') {
            window.TobyMusicQueue.attach(list, (from, to) => {
                post('/playlists/' + detail.id + '/items/reorder', { from: from, to: to }).then((res) => {
                    if (res && res.ok && res.detail) renderPlaylistEditor(panel, res.detail);
                    else toast((res && res.message) || 'Could not reorder.', 'error');
                });
            }, 'li.pl-track');
        }
    }

    async function deletePlaylist(pl) {
        // Shared modal (modal.js) for the confirm; fall back to native if absent.
        const ok = window.TobyModal
            ? await window.TobyModal.confirm({
                title: 'Delete playlist?',
                body: '“' + pl.name + '” will be removed permanently.',
                confirmLabel: 'Delete',
                cancelLabel: 'Cancel',
            })
            : window.confirm('Delete playlist "' + pl.name + '"?');
        if (!ok) return;
        del('/playlists/' + pl.id).then((res) => {
            if (res && res.ok === false) {
                toast(res.message || 'Could not delete playlist.', 'error');
                return;
            }
            if (String(pl.id) === String(expandedPlaylistId)) expandedPlaylistId = null;
            toast('Playlist deleted.', 'success');
            refreshPlaylists();
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
            li.className = 'member-cell voice-member';
            if (m.isBot) li.classList.add('is-bot');

            const img = document.createElement('img');
            img.className = 'avatar';
            img.alt = '';
            if (m.avatarUrl) img.src = m.avatarUrl;
            li.appendChild(img);

            const name = document.createElement('span');
            name.className = 'lb-name';
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
        // If a preview is currently playing from a result row, stop it
        // (otherwise the audio would keep playing against an orphaned button
        // reference). Queue-item previews are left alone.
        if (previewController.isActiveIn(els.searchList)) previewController.reset();
        els.searchSection.hidden = true;
        els.searchList.innerHTML = '';
        els.searchEmpty.hidden = true;
        els.searchQueryLabel.textContent = '';
    }

    // ---- Add-to-playlist picker (search results) -----------------------
    // Curating a playlist never queues or plays the track — it POSTs the
    // resolved metadata straight to /playlists/{id}/items.

    function closeAllPlaylistMenus(except) {
        els.searchList.querySelectorAll('.add-to-playlist-menu').forEach((m) => {
            if (m !== except) m.hidden = true;
        });
    }
    // One document-level listener closes any open picker on an outside click.
    document.addEventListener('click', (e) => {
        if (!e.target.closest('.add-to-playlist')) closeAllPlaylistMenus(null);
    });

    function addTrackToPlaylist(playlistId, track) {
        return post('/playlists/' + playlistId + '/items', {
            identifier: track.uri || track.identifier,
            title: track.title,
            author: track.author,
            durationMs: track.durationMs,
            sourceName: track.sourceName,
        }).then((res) => {
            if (res && res.ok) {
                toast('Added to playlist.', 'success');
                refreshPlaylists();
            } else {
                toast((res && res.message) || 'Could not add to playlist.', 'error');
            }
            return res;
        });
    }

    function buildPlaylistMenu(track, menu) {
        menu.innerHTML = '';
        const owned = playlistsCache.filter(ownsPlaylist);
        if (owned.length > 0) {
            owned.forEach((pl) => {
                const opt = makeEl('button', 'add-to-playlist-option');
                opt.type = 'button';
                opt.appendChild(makeEl('span', 'add-to-playlist-option-name', pl.name));
                opt.appendChild(makeEl('span', 'add-to-playlist-option-count muted', trackCountLabel(pl.trackCount)));
                opt.addEventListener('click', () => {
                    menu.hidden = true;
                    addTrackToPlaylist(pl.id, track);
                });
                menu.appendChild(opt);
            });
        } else {
            menu.appendChild(makeEl('p', 'add-to-playlist-empty muted', 'No playlists yet — name one below.'));
        }

        // Inline "create + add" so a playlist can be born from a search hit.
        const newForm = makeEl('form', 'add-to-playlist-new');
        newForm.autocomplete = 'off';
        const input = makeEl('input', 'add-to-playlist-new-input');
        input.type = 'text';
        input.maxLength = 80;
        input.placeholder = 'New playlist…';
        const addBtn = makeEl('button', 'btn-tertiary', '＋ Add');
        addBtn.type = 'submit';
        newForm.appendChild(input);
        newForm.appendChild(addBtn);
        newForm.addEventListener('submit', (e) => {
            e.preventDefault();
            const name = input.value.trim();
            if (!name) return;
            addBtn.disabled = true;
            post('/playlists', { name: name, fromQueue: false }).then((res) => {
                if (res && res.ok && res.id) {
                    menu.hidden = true;
                    addTrackToPlaylist(res.id, track);
                } else {
                    addBtn.disabled = false;
                    toast((res && res.message) || 'Could not create playlist.', 'error');
                }
            });
        });
        menu.appendChild(newForm);
    }

    function makeAddToPlaylistControl(track) {
        const wrap = makeEl('div', 'add-to-playlist');
        const btn = makeEl('button', 'btn-add-playlist', '＋ Playlist');
        btn.type = 'button';
        btn.setAttribute('aria-haspopup', 'true');
        btn.setAttribute('aria-expanded', 'false');
        btn.title = 'Add to a playlist without playing';
        const menu = makeEl('div', 'add-to-playlist-menu');
        menu.hidden = true;
        btn.addEventListener('click', () => {
            const opening = menu.hidden;
            closeAllPlaylistMenus(menu);
            if (opening) {
                buildPlaylistMenu(track, menu);
                menu.hidden = false;
            } else {
                menu.hidden = true;
            }
            btn.setAttribute('aria-expanded', String(opening));
        });
        wrap.appendChild(btn);
        wrap.appendChild(menu);
        return wrap;
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

            const previewBtn = makePreviewBtn(track);
            if (previewBtn) li.appendChild(previewBtn);

            li.appendChild(makeAddToPlaylistControl(track));

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
                        toast('Added to queue.', 'success');
                    } else {
                        queueBtn.disabled = false;
                        queueBtn.textContent = 'Queue';
                        toast((res && res.message) || 'Could not queue that track.', 'error');
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
                    toast('Added to queue.', 'success');
                } else {
                    toast((res && res.message) || 'Could not queue that link.', 'error');
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

    // Two ways to create: the form submit (“＋ New”) makes an empty playlist
    // to curate from search; “💾 Save queue” snapshots the live queue.
    function createPlaylist(fromQueue) {
        const name = els.savePlaylistName.value.trim();
        if (!name) return;
        post('/playlists', { name: name, fromQueue: fromQueue }).then((res) => {
            if (res && res.ok) {
                els.savePlaylistName.value = '';
                if (res.id != null) expandedPlaylistId = res.id;
                refreshPlaylists();
                toast(fromQueue ? 'Queue saved as playlist.' : 'Playlist created.', 'success');
            } else {
                toast((res && res.message) || 'Could not create playlist.', 'error');
            }
        });
    }

    els.savePlaylistForm.addEventListener('submit', (e) => {
        e.preventDefault();
        createPlaylist(false);
    });
    if (els.saveQueueBtn) {
        els.saveQueueBtn.addEventListener('click', () => createPlaylist(true));
    }

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
            // Only flip the flourish when a track is actually loaded — a
            // pause event with the empty state showing shouldn't light up.
            setPlayingFlourish(!lastPaused && !els.content.hidden);
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
