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
            case 'ATTACK_HIT': {
                const ac = (p.targetAc != null) ? ' vs AC ' + p.targetAc : '';
                return (p.attacker || '?') + ' hits ' + (p.target || '?') + ' — ' + p.total + ac;
            }
            case 'ATTACK_MISS': {
                const ac = (p.targetAc != null) ? ' vs AC ' + p.targetAc : '';
                return (p.attacker || '?') + ' misses ' + (p.target || '?') + ' — ' + p.total + ac;
            }
            case 'DAMAGE_DEALT': {
                const remaining = (p.remainingHp != null) ? ' (' + p.remainingHp + ' HP left)' : '';
                return (p.target || '?') + ' takes ' + p.amount + ' damage' + remaining;
            }
            case 'PARTICIPANT_DEFEATED':
                return (p.target || '?') + ' is defeated';
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

    // Turn-table widget: listens to the same stream and reflects initiative state.
    const turnTable = document.getElementById('turn-table');
    const turnList = document.getElementById('turn-table-list');
    const turnEmpty = document.getElementById('turn-table-empty');

    function ensureList() {
        if (turnList) return turnList;
        if (!turnTable) return null;
        const ol = document.createElement('ol');
        ol.id = 'turn-table-list';
        turnTable.appendChild(ol);
        return ol;
    }

    const prefersReducedMotion = window.matchMedia
        && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    function buildD20(roll, rolling) {
        const die = document.createElement('span');
        die.className = rolling ? 'd20 rolling' : 'd20';
        die.dataset.roll = String(roll);
        const face = document.createElement('span');
        face.className = 'd20__face';
        face.textContent = rolling ? '?' : String(roll);
        die.appendChild(face);
        return die;
    }

    function rebuildTurnTable(entries, currentIndex, options) {
        if (!turnTable) return;
        const list = ensureList();
        if (!list) return;
        const animate = !!(options && options.animate) && !prefersReducedMotion;
        list.innerHTML = '';
        if (!entries.length) {
            if (turnEmpty) turnEmpty.style.display = '';
            return;
        }
        if (turnEmpty) turnEmpty.style.display = 'none';
        entries.forEach(function (entry, i) {
            const li = document.createElement('li');
            const classes = [];
            if (i === currentIndex) classes.push('active');
            if (entry.defeated) classes.push('defeated');
            if (classes.length) li.className = classes.join(' ');
            li.dataset.name = entry.name || '';
            const idx = document.createElement('span');
            idx.className = 'idx';
            idx.textContent = (i + 1);
            const name = document.createElement('span');
            name.className = 'name';
            name.textContent = entry.name || 'Unknown';
            li.appendChild(idx);
            li.appendChild(name);
            if (entry.kind === 'PLAYER' || entry.kind === 'MONSTER') {
                const chip = document.createElement('span');
                chip.className = 'chip ' + entry.kind.toLowerCase();
                chip.textContent = entry.kind === 'PLAYER' ? 'Player' : 'Monster';
                li.appendChild(chip);
            }
            if (entry.ac != null) {
                const ac = document.createElement('span');
                ac.className = 'ac-chip';
                ac.textContent = 'AC ' + entry.ac;
                li.appendChild(ac);
            }
            if (entry.maxHp != null) {
                const wrap = document.createElement('span');
                wrap.className = 'hp-wrap';
                const bar = document.createElement('span');
                bar.className = 'hp-bar';
                const fill = document.createElement('span');
                fill.className = 'hp-fill';
                const hp = entry.currentHp == null ? entry.maxHp : entry.currentHp;
                const pct = entry.maxHp > 0 ? Math.max(0, Math.min(100, (hp * 100) / entry.maxHp)) : 0;
                if (pct < 15) fill.classList.add('low');
                else if (pct < 40) fill.classList.add('mid');
                fill.style.width = pct + '%';
                bar.appendChild(fill);
                wrap.appendChild(bar);
                const label = document.createElement('span');
                label.className = 'hp-label';
                const cur = document.createElement('span');
                cur.textContent = String(hp);
                const max = document.createElement('span');
                max.textContent = String(entry.maxHp);
                label.appendChild(cur);
                label.appendChild(document.createTextNode('/'));
                label.appendChild(max);
                wrap.appendChild(label);
                li.appendChild(wrap);
            }
            li.appendChild(buildD20(entry.roll, animate));
            list.appendChild(li);
        });
        turnTable.dataset.currentIndex = String(currentIndex);
        if (animate) scheduleReveal(list, entries);
    }

    function scheduleReveal(list, entries) {
        const staggerMs = 280;
        const popMs = 420;
        entries.forEach(function (entry, i) {
            const li = list.children[i];
            if (!li) return;
            const die = li.querySelector('.d20');
            if (!die) return;
            setTimeout(function () {
                die.classList.remove('rolling');
                die.classList.add('settled');
                const face = die.querySelector('.d20__face');
                if (face) face.textContent = String(entry.roll);
                setTimeout(function () { die.classList.remove('settled'); }, popMs);
            }, (i + 1) * staggerMs);
        });
    }

    function highlightIndex(idx) {
        if (!turnTable) return;
        const list = turnTable.querySelector('ol');
        if (!list) return;
        Array.prototype.forEach.call(list.children, function (node, i) {
            if (i === idx) node.classList.add('active');
            else node.classList.remove('active');
        });
        turnTable.dataset.currentIndex = String(idx);
    }

    function findRow(name) {
        if (!turnTable || !name) return null;
        const list = turnTable.querySelector('ol');
        if (!list) return null;
        return list.querySelector('li[data-name="' + String(name).replace(/"/g, '\\"') + '"]');
    }

    function flashRow(name, cls) {
        const row = findRow(name);
        if (!row) return;
        row.classList.remove('just-hit', 'just-missed');
        // reflow so the animation re-triggers
        void row.offsetWidth;
        row.classList.add(cls);
    }

    function applyDamageToRow(name, remainingHp) {
        const row = findRow(name);
        if (!row) return;
        const fill = row.querySelector('.hp-fill');
        const label = row.querySelector('.hp-label span:first-child');
        if (!fill || !row.querySelector('.hp-wrap')) return;
        const maxSpan = row.querySelectorAll('.hp-label span')[1];
        const max = maxSpan ? parseInt(maxSpan.textContent, 10) : 0;
        const hp = Math.max(0, remainingHp == null ? 0 : remainingHp);
        if (label) label.textContent = String(hp);
        if (max > 0) {
            const pct = Math.max(0, Math.min(100, (hp * 100) / max));
            fill.style.width = pct + '%';
            fill.classList.remove('low', 'mid');
            if (pct < 15) fill.classList.add('low');
            else if (pct < 40) fill.classList.add('mid');
        }
    }

    function markDefeatedRow(name) {
        const row = findRow(name);
        if (!row) return;
        row.classList.add('defeated');
    }

    function handleInitiativeEvent(event) {
        if (!turnTable) return;
        const p = event.payload || {};
        switch (event.type) {
            case 'INITIATIVE_ROLLED': {
                const entries = Array.isArray(p.entries) ? p.entries : [];
                rebuildTurnTable(entries, 0, { animate: true });
                break;
            }
            case 'INITIATIVE_NEXT':
            case 'INITIATIVE_PREV': {
                const idx = typeof p.currentIndex === 'number' ? p.currentIndex : 0;
                highlightIndex(idx);
                break;
            }
            case 'INITIATIVE_CLEARED': {
                rebuildTurnTable([], 0);
                break;
            }
            case 'ATTACK_HIT':
                flashRow(p.target, 'just-hit');
                break;
            case 'ATTACK_MISS':
                flashRow(p.target, 'just-missed');
                break;
            case 'DAMAGE_DEALT':
                applyDamageToRow(p.target, p.remainingHp);
                break;
            case 'PARTICIPANT_DEFEATED':
                markDefeatedRow(p.target);
                break;
        }
    }

    const source = new EventSource('/dnd/campaign/' + guildId + '/events/stream');
    source.addEventListener('event', function (e) {
        try {
            const parsed = JSON.parse(e.data);
            renderEvent(parsed);
            handleInitiativeEvent(parsed);
        } catch (_) { /* skip malformed */ }
    });
    source.addEventListener('error', function () {
        // EventSource retries automatically; nothing to do here.
    });
})();
