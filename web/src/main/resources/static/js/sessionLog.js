(function () {
    const logEl = document.getElementById('session-log');
    if (!logEl) return;

    const guildId = logEl.dataset.guildId;
    if (!guildId) return;

    const isDm = logEl.dataset.isDm === 'true';
    let lastSeenId = parseInt(logEl.dataset.lastSeenId || '0', 10);
    const emptyEl = document.getElementById('session-log-empty');

    function postAnnotation(eventId, kind) {
        const form = document.createElement('form');
        form.method = 'POST';
        form.action = '/dnd/campaign/' + guildId + '/events/' + eventId + '/annotate';
        const input = document.createElement('input');
        input.type = 'hidden';
        input.name = 'kind';
        input.value = kind;
        form.appendChild(input);
        document.body.appendChild(form);
        form.submit();
    }

    function buildDmActions(event) {
        if (!isDm || event.type !== 'ROLL') return null;
        const wrap = document.createElement('span');
        wrap.className = 'event-dm-actions';
        const hit = document.createElement('button');
        hit.type = 'button';
        hit.className = 'btn-xs';
        hit.title = 'Mark this roll as a hit';
        hit.textContent = 'Hit';
        hit.addEventListener('click', function () { postAnnotation(event.id, 'HIT'); });
        const miss = document.createElement('button');
        miss.type = 'button';
        miss.className = 'btn-xs kick';
        miss.title = 'Mark this roll as a miss';
        miss.textContent = 'Miss';
        miss.addEventListener('click', function () { postAnnotation(event.id, 'MISS'); });
        wrap.appendChild(hit);
        wrap.appendChild(miss);
        return wrap;
    }

    function ensureContainer() {
        if (emptyEl) emptyEl.style.display = 'none';
    }

    function escapeText(v) {
        if (v === null || v === undefined) return '';
        return String(v);
    }

    function renderBody(event) {
        const p = event.payload || {};
        switch (event.type) {
            case 'ROLL': {
                const mod = p.modifier ? ' + ' + p.modifier : '';
                return 'rolled ' + p.count + 'd' + p.sides + mod + ' = ' + p.total;
            }
            case 'INITIATIVE_ROLLED': {
                const entries = Array.isArray(p.entries) ? p.entries : [];
                if (!entries.length) return 'rolled initiative (empty)';
                const order = entries.map(function (e) { return e.name + ' ' + e.roll; }).join(', ');
                return 'rolled initiative — ' + order;
            }
            case 'INITIATIVE_NEXT':
                return p.currentName ? 'next turn: ' + p.currentName : 'advanced initiative';
            case 'INITIATIVE_PREV':
                return p.currentName ? 'back to: ' + p.currentName : 'rewound initiative';
            case 'INITIATIVE_CLEARED':
                return 'cleared initiative';
            case 'PLAYER_JOINED':
                return 'joined the campaign';
            case 'PLAYER_LEFT':
                return 'left the campaign';
            case 'PLAYER_KICKED':
                return 'kicked ' + (p.targetName || 'player ' + p.targetDiscordId);
            case 'PLAYER_DIED':
                return '☠️ marked ' + (p.targetName || 'player') + ' as dead';
            case 'PLAYER_REVIVED':
                return 'revived ' + (p.targetName || 'player');
            case 'CAMPAIGN_ENDED':
                return p.campaignName
                    ? 'ended campaign “' + p.campaignName + '”'
                    : 'ended the campaign';
            case 'DM_NOTE':
                return p.body ? p.body : '(empty note)';
            case 'HIT':
                return 'marked as HIT' + (p.target ? ' on ' + p.target : '');
            case 'MISS':
                return 'marked as MISS' + (p.target ? ' on ' + p.target : '');
            default:
                return escapeText(JSON.stringify(p));
        }
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
        body.textContent = renderBody(event);

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
        const dmActions = buildDmActions(event);
        if (dmActions) row.appendChild(dmActions);
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
