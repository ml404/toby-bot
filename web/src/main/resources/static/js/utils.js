(function () {
    'use strict';

    function toast(msg, type) {
        const level = type || 'info';

        if (window.TobyToasts && typeof window.TobyToasts[level] === 'function') {
            window.TobyToasts[level](msg);
        } else {
            console.log('[' + level + '] ' + msg);
        }
    }

    function output(name) {
        return document.querySelector('[data-output="' + name + '"]');
    }

    function fetchJson(url) {
        return fetch(url, { credentials: 'same-origin', headers: { 'Accept': 'application/json' } })
            .then(function (r) {
                return r.json().catch(function () {
                    return { ok: r.ok, error: r.ok ? null : 'Request failed.' };
                });
            });
    }

    function randInt(maxInclusive) {
        return 1 + Math.floor(Math.random() * maxInclusive);
    }

    // --- Dice roller ---
    const diceForm = document.querySelector('[data-form="dice"]');
    if (diceForm) {
        diceForm.addEventListener('submit', e => {
            e.preventDefault();
            const count = parseInt(diceForm.elements.count.value, 10);
            const sides = parseInt(diceForm.elements.sides.value, 10);
            const modifier = parseInt(diceForm.elements.modifier.value, 10) || 0;
            if (!Number.isFinite(count) || count < 1 || count > 100) {
                toast('Count must be between 1 and 100.', 'error');
                return;
            }
            if (!Number.isFinite(sides) || sides < 2 || sides > 1000) {
                toast('Sides must be between 2 and 1000.', 'error');
                return;
            }
            const rolls = [];
            let total = 0;
            for (let i = 0; i < count; i++) {
                const r = randInt(sides);
                rolls.push(r);
                total += r;
            }
            const grand = total + modifier;
            const label = count + 'd' + sides + (modifier ? (modifier > 0 ? ' + ' + modifier : ' - ' + Math.abs(modifier)) : '');
            output('dice').textContent =
                label + '\nRolls: [' + rolls.join(', ') + ']\nSum: ' + total +
                (modifier ? '\nWith modifier: ' + grand : '');
        });
    }

    // --- Random chooser ---
    const randomForm = document.querySelector('[data-form="random"]');
    if (randomForm) {
        randomForm.addEventListener('submit', e => {
            e.preventDefault();
            const raw = randomForm.elements.list.value || '';
            const items = raw.split(',').map(s => s.trim()).filter(s => s.length > 0);
            if (items.length === 0) {
                toast('Provide at least one option.', 'error');
                return;
            }
            const pick = items[Math.floor(Math.random() * items.length)];
            output('random').textContent = '> ' + pick;
        });
    }

    // --- Magic 8-ball ---
    const EIGHTBALL_RESPONSES = [
        'It is certain',
        'It is decidedly so',
        'Without a doubt',
        'Yes - definitely',
        'You may rely on it',
        'As I see it, yes',
        'Most likely',
        'Outlook good',
        'Signs point to yes',
        'Yes',
        'Reply hazy, try again',
        'Ask again later',
        'Better not tell you now',
        'Cannot predict now',
        'Concentrate and ask again',
        "Don't count on it",
        'My reply is no',
        'My sources say no',
        'Outlook not so good',
        'Very doubtful'
    ];

    const eightballBtn = document.querySelector('[data-action="eightball"]');
    if (eightballBtn) {
        eightballBtn.addEventListener('click', () => {
            const choice = EIGHTBALL_RESPONSES[Math.floor(Math.random() * EIGHTBALL_RESPONSES.length)];
            output('eightball').textContent = 'MAGIC 8-BALL SAYS: ' + choice + '.';
        });
    }

    // --- Meme fetcher ---
    const memeForm = document.querySelector('[data-form="meme"]');
    if (memeForm) {
        memeForm.addEventListener('submit', e => {
            e.preventDefault();
            const subreddit = memeForm.elements.subreddit.value.trim();
            const timePeriod = memeForm.elements.timePeriod.value;
            const limit = parseInt(memeForm.elements.limit.value, 10) || 10;
            if (!subreddit) {
                toast('Subreddit is required.', 'error');
                return;
            }
            const submit = memeForm.querySelector('button[type="submit"]');
            submit.disabled = true;
            const params = new URLSearchParams({
                subreddit: subreddit,
                timePeriod: timePeriod,
                limit: String(limit)
            });
            fetchJson('/utils/api/meme?' + params.toString())
                .then(r => {
                    submit.disabled = false;
                    const slot = output('meme');
                    if (r && r.ok && r.meme) {
                        slot.innerHTML = '';
                        const title = document.createElement('div');
                        title.className = 'meme-title';
                        const link = document.createElement('a');
                        link.href = r.meme.permalink;
                        link.target = '_blank';
                        link.rel = 'noopener';
                        link.textContent = r.meme.title;
                        title.appendChild(link);
                        const author = document.createElement('div');
                        author.className = 'muted';
                        author.textContent = 'by u/' + r.meme.author + ' in r/' + r.meme.subreddit;
                        const img = document.createElement('img');
                        img.src = r.meme.imageUrl;
                        img.alt = r.meme.title;
                        img.className = 'meme-image';
                        img.loading = 'lazy';
                        slot.appendChild(title);
                        slot.appendChild(author);
                        slot.appendChild(img);
                    } else {
                        slot.textContent = r?.error || 'Could not fetch meme.';
                        toast(r?.error || 'Could not fetch meme.', 'error');
                    }
                })
                .catch(() => { submit.disabled = false; toast('Network error.', 'error'); });
        });
    }

    // --- DBD killer ---
    const dbdBtn = document.querySelector('[data-action="dbd"]');
    if (dbdBtn) {
        dbdBtn.addEventListener('click', () => {
            dbdBtn.disabled = true;
            fetchJson('/utils/api/dbd-killer')
                .then(r => {
                    dbdBtn.disabled = false;
                    if (r && r.ok && r.text) {
                        output('dbd').textContent = r.text;
                    } else {
                        output('dbd').textContent = r?.error || 'Could not fetch killer.';
                        toast(r?.error || 'Could not fetch killer.', 'error');
                    }
                })
                .catch(() => { dbdBtn.disabled = false; toast('Network error.', 'error'); });
        });
    }

    // --- KF2 map ---
    const kf2Btn = document.querySelector('[data-action="kf2"]');
    if (kf2Btn) {
        kf2Btn.addEventListener('click', () => {
            kf2Btn.disabled = true;
            fetchJson('/utils/api/kf2-map')
                .then(r => {
                    kf2Btn.disabled = false;
                    if (r && r.ok && r.text) {
                        output('kf2').textContent = r.text;
                    } else {
                        output('kf2').textContent = r?.error || 'Could not fetch map.';
                        toast(r?.error || 'Could not fetch map.', 'error');
                    }
                })
                .catch(() => { kf2Btn.disabled = false; toast('Network error.', 'error'); });
        });
    }
})();
