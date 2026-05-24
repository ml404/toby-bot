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

    function stage(name) {
        return document.querySelector('[data-stage="' + name + '"]');
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

    const reducedMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    // --- Dice roller ---
    const MAX_VISIBLE_DICE = 24;

    function makeDie(sides, value) {
        if (sides === 6 && value >= 1 && value <= 6) {
            const img = document.createElement('img');
            img.className = 'die';
            img.alt = 'Die showing ' + value;
            img.decoding = 'async';
            img.src = '/images/utils/d6-' + value + '.svg';
            return img;
        }
        // Numbered die: shared SVG body with the value overlaid via CSS so
        // we don't have to refetch & rewrite the SVG per roll.
        const wrap = document.createElement('span');
        wrap.className = 'die die-num';
        wrap.setAttribute('role', 'img');
        wrap.setAttribute('aria-label', 'Die showing ' + value);
        const face = document.createElement('img');
        face.src = '/images/utils/die-num.svg';
        face.alt = '';
        face.decoding = 'async';
        face.className = 'die-num-face';
        const numEl = document.createElement('span');
        numEl.className = 'die-num-value';
        numEl.textContent = String(value);
        // Shrink the digit slot for very large values so it always fits.
        const digits = String(value).length;
        if (digits >= 4) numEl.classList.add('die-num-value-xs');
        else if (digits === 3) numEl.classList.add('die-num-value-sm');
        else if (digits === 2) numEl.classList.add('die-num-value-md');
        wrap.appendChild(face);
        wrap.appendChild(numEl);
        return wrap;
    }

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

            const tray = stage('dice');
            tray.innerHTML = '';
            const visible = Math.min(rolls.length, MAX_VISIBLE_DICE);
            for (let i = 0; i < visible; i++) {
                const die = makeDie(sides, rolls[i]);
                if (!reducedMotion) {
                    die.classList.add('rolling');
                    die.style.animationDelay = (i * 40) + 'ms';
                    die.addEventListener('animationend', function onEnd() {
                        die.removeEventListener('animationend', onEnd);
                        die.classList.remove('rolling');
                        die.classList.add('settled');
                    }, { once: true });
                }
                tray.appendChild(die);
            }
            if (rolls.length > MAX_VISIBLE_DICE) {
                const more = document.createElement('span');
                more.className = 'die-more';
                more.textContent = '+' + (rolls.length - MAX_VISIBLE_DICE) + ' more';
                tray.appendChild(more);
            }

            output('dice').textContent =
                label + '\nRolls: [' + rolls.join(', ') + ']\nSum: ' + total +
                (modifier ? '\nWith modifier: ' + grand : '');
        });
    }

    // --- Random chooser ---
    const REEL_ROW_HEIGHT = 44;
    const REEL_REPEATS = 8;

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
            const pickIndex = Math.floor(Math.random() * items.length);
            const pick = items[pickIndex];

            const reelStage = stage('random');
            const strip = reelStage.querySelector('.reel-strip');
            strip.innerHTML = '';
            strip.style.transition = 'none';
            strip.style.transform = 'translateY(0)';

            // Build a strip several rotations long so the spin has runway.
            // Land on the LAST occurrence of the winner so users see scrolling
            // before it settles.
            const stripItems = [];
            for (let r = 0; r < REEL_REPEATS; r++) {
                for (let i = 0; i < items.length; i++) {
                    stripItems.push(items[i]);
                }
            }
            const winnerStripIndex = stripItems.length - items.length + pickIndex;

            stripItems.forEach((label, idx) => {
                const li = document.createElement('li');
                li.textContent = label;
                if (idx === winnerStripIndex) {
                    li.dataset.winner = 'true';
                }
                strip.appendChild(li);
            });

            // Force a reflow so the next transform animates.
            void strip.offsetHeight;

            const targetY = -((winnerStripIndex - 1) * REEL_ROW_HEIGHT);
            if (reducedMotion) {
                strip.style.transform = 'translateY(' + targetY + 'px)';
                const winner = strip.querySelector('[data-winner="true"]');
                if (winner) winner.classList.add('is-winner');
            } else {
                strip.style.transition = 'transform 1200ms cubic-bezier(0.18, 0.7, 0.2, 1)';
                strip.style.transform = 'translateY(' + targetY + 'px)';
                strip.addEventListener('transitionend', function onDone() {
                    strip.removeEventListener('transitionend', onDone);
                    const winner = strip.querySelector('[data-winner="true"]');
                    if (winner) winner.classList.add('is-winner');
                }, { once: true });
            }

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
    const ballStage = stage('eightball');
    if (eightballBtn && ballStage) {
        const windowEl = ballStage.querySelector('.ball-window');
        const textEl = ballStage.querySelector('.ball-window-text');

        function revealAnswer() {
            const choice = EIGHTBALL_RESPONSES[Math.floor(Math.random() * EIGHTBALL_RESPONSES.length)];
            textEl.textContent = choice;
            windowEl.setAttribute('aria-label', 'Magic 8-ball window: ' + choice);
            ballStage.classList.remove('is-revealed');
            void ballStage.offsetWidth;
            ballStage.classList.add('is-revealed');
            eightballBtn.disabled = false;
        }

        eightballBtn.addEventListener('click', () => {
            if (eightballBtn.disabled) return;
            eightballBtn.disabled = true;
            ballStage.classList.remove('is-revealed');
            if (reducedMotion) {
                revealAnswer();
                return;
            }
            ballStage.classList.add('is-shaking');
            ballStage.addEventListener('animationend', function onShake() {
                ballStage.removeEventListener('animationend', onShake);
                ballStage.classList.remove('is-shaking');
                revealAnswer();
            }, { once: true });
        });
    }

    // --- Meme fetcher ---
    const memeForm = document.querySelector('[data-form="meme"]');
    const memeFrame = stage('meme');
    const memeReroll = document.querySelector('[data-action="meme-reroll"]');

    function renderMeme(data) {
        const slot = output('meme');
        slot.innerHTML = '';
        const title = document.createElement('div');
        title.className = 'meme-title';
        const link = document.createElement('a');
        link.href = data.permalink;
        link.target = '_blank';
        link.rel = 'noopener';
        link.textContent = data.title;
        title.appendChild(link);
        const author = document.createElement('div');
        author.className = 'muted';
        author.textContent = 'by u/' + data.author + ' in r/' + data.subreddit;
        const img = document.createElement('img');
        img.alt = data.title;
        img.className = 'meme-image';
        img.loading = 'lazy';
        img.addEventListener('load', () => img.classList.add('is-loaded'), { once: true });
        img.addEventListener('error', () => {
            img.classList.add('is-loaded');
            img.alt = 'Image failed to load';
        }, { once: true });
        img.src = data.imageUrl;
        slot.appendChild(title);
        slot.appendChild(author);
        slot.appendChild(img);
    }

    function renderMemeError(msg) {
        const slot = output('meme');
        slot.innerHTML = '';
        const p = document.createElement('p');
        p.className = 'meme-empty';
        p.textContent = msg;
        slot.appendChild(p);
    }

    function fetchMeme(submitButton) {
        const subreddit = memeForm.elements.subreddit.value.trim();
        const timePeriod = memeForm.elements.timePeriod.value;
        const limit = parseInt(memeForm.elements.limit.value, 10) || 10;
        if (!subreddit) {
            toast('Subreddit is required.', 'error');
            return;
        }
        submitButton.disabled = true;
        if (memeReroll) memeReroll.disabled = true;
        memeFrame.classList.add('is-loading');
        const params = new URLSearchParams({
            subreddit: subreddit,
            timePeriod: timePeriod,
            limit: String(limit)
        });
        fetchJson('/utils/api/meme?' + params.toString())
            .then(r => {
                submitButton.disabled = false;
                if (memeReroll) memeReroll.disabled = false;
                memeFrame.classList.remove('is-loading');
                if (r && r.ok && r.meme) {
                    renderMeme(r.meme);
                    if (memeReroll) memeReroll.hidden = false;
                } else {
                    const msg = (r && r.error) || 'Could not fetch meme.';
                    renderMemeError(msg);
                    toast(msg, 'error');
                }
            })
            .catch(() => {
                submitButton.disabled = false;
                if (memeReroll) memeReroll.disabled = false;
                memeFrame.classList.remove('is-loading');
                renderMemeError('Network error.');
                toast('Network error.', 'error');
            });
    }

    if (memeForm && memeFrame) {
        memeForm.addEventListener('submit', e => {
            e.preventDefault();
            fetchMeme(memeForm.querySelector('button[type="submit"]'));
        });
        if (memeReroll) {
            memeReroll.addEventListener('click', () => {
                fetchMeme(memeForm.querySelector('button[type="submit"]'));
            });
        }
    }
})();
