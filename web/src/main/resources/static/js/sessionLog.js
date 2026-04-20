(function () {
    const logEl = document.getElementById('session-log');
    if (!logEl) return;

    const guildId = logEl.dataset.guildId;
    if (!guildId) return;

    let lastSeenId = parseInt(logEl.dataset.lastSeenId || '0', 10);
    const emptyEl = document.getElementById('session-log-empty');

    function ensureContainer() {
        if (emptyEl) emptyEl.style.display = 'none';
    }

    function escapeText(v) {
        if (v === null || v === undefined) return '';
        return String(v);
    }

    function renderRollBody(p) {
        const sides = p.sides;
        const count = p.count;
        const modifier = p.modifier || 0;
        const total = p.total;
        const modSegment = modifier ? ' + ' + modifier : '';
        return 'rolled ' + count + 'd' + sides + modSegment + ' = ' + total;
    }

    function renderEvent(event) {
        if (!event || typeof event.id !== 'number') return;
        if (event.id <= lastSeenId) return;
        lastSeenId = event.id;
        ensureContainer();

        const row = document.createElement('div');
        row.className = 'event-row';

        const type = document.createElement('span');
        type.className = 'event-type';
        type.textContent = event.type;

        const actor = document.createElement('span');
        actor.className = 'event-actor';
        actor.textContent = event.actorName || 'system';

        const body = document.createElement('span');
        body.className = 'event-body';
        const payload = event.payload || {};
        if (event.type === 'ROLL') {
            body.textContent = renderRollBody(payload);
        } else {
            body.textContent = escapeText(JSON.stringify(payload));
        }

        const time = document.createElement('span');
        time.className = 'event-time';
        const when = event.createdAt ? new Date(event.createdAt) : new Date();
        time.textContent = isNaN(when.getTime())
            ? ''
            : when.toLocaleTimeString(undefined, { hour12: false });

        row.appendChild(type);
        row.appendChild(actor);
        row.appendChild(body);
        row.appendChild(time);
        logEl.appendChild(row);
    }

    // Backfill anything that landed between server render and JS boot, then subscribe.
    fetch('/dnd/campaign/' + guildId + '/events?since=' + lastSeenId + '&limit=200', {
        credentials: 'same-origin'
    })
        .then(function (r) { return r.ok ? r.json() : []; })
        .then(function (events) {
            if (Array.isArray(events)) events.forEach(renderEvent);
        })
        .catch(function () { /* non-fatal; SSE will catch up */ });

    const source = new EventSource('/dnd/campaign/' + guildId + '/events/stream');
    source.addEventListener('event', function (e) {
        try { renderEvent(JSON.parse(e.data)); } catch (_) { /* skip malformed */ }
    });
    source.addEventListener('error', function () {
        // EventSource retries automatically; nothing to do here.
    });
})();
