(function () {
    'use strict';

    const main = document.querySelector('main[data-guild-id]');
    if (!main) return;

    const guildId = main.dataset.guildId;
    const postJson = window.TobyApi && window.TobyApi.postJson;

    function toast(msg, type) {
        if (window.TobyToast && typeof window.TobyToast.show === 'function') {
            window.TobyToast.show(msg, { type: type || 'info' });
        } else {
            console.log('[' + (type || 'info') + '] ' + msg);
        }
    }

    const die = document.getElementById('dice-die');
    const dieFace = document.getElementById('dice-die-face');
    const stakeInput = document.getElementById('dice-stake');
    const rollBtn = document.getElementById('dice-roll');
    const balanceEl = document.getElementById('dice-balance');
    const resultEl = document.getElementById('dice-result');
    const form = document.getElementById('dice-bet');

    if (!form || !rollBtn || !stakeInput || !die || !dieFace) return;

    const FACES = { 1: '⚀', 2: '⚁', 3: '⚂', 4: '⚃', 5: '⚄', 6: '⚅' };
    const ROLL_DURATION_MS = 800;
    const ROLL_INTERVAL_MS = 70;

    let rolling = false;

    function selectedPrediction() {
        const checked = form.querySelector('input[name="prediction"]:checked');
        return checked ? parseInt(checked.value, 10) : null;
    }

    function startRollAnimation() {
        rolling = true;
        die.classList.add('rolling');
        return setInterval(function () {
            const n = 1 + Math.floor(Math.random() * 6);
            dieFace.textContent = FACES[n] || String(n);
        }, ROLL_INTERVAL_MS);
    }

    function stopRollAnimation(intervalId, landed) {
        clearInterval(intervalId);
        rolling = false;
        die.classList.remove('rolling');
        if (landed && FACES[landed]) {
            dieFace.textContent = FACES[landed];
            die.dataset.landed = String(landed);
        } else {
            dieFace.textContent = '⚀';
            delete die.dataset.landed;
        }
    }

    function showResult(body) {
        if (!resultEl) return;
        resultEl.hidden = false;
        resultEl.classList.remove('dice-result-win', 'dice-result-lose');
        if (body.win) {
            resultEl.classList.add('dice-result-win');
            resultEl.innerHTML = '<strong>' + body.landed + '!</strong> You called ' +
                body.predicted + ' &middot; <strong>+' + body.net + ' credits</strong>';
        } else {
            resultEl.classList.add('dice-result-lose');
            resultEl.innerHTML = '<strong>' + body.landed + '.</strong> You called ' +
                body.predicted + ' &middot; lost <strong>' + Math.abs(body.net) + ' credits</strong>';
        }
    }

    function applyBalance(newBalance) {
        if (typeof newBalance !== 'number') return;
        if (balanceEl) balanceEl.textContent = newBalance;
    }

    form.addEventListener('submit', function (e) {
        e.preventDefault();
        if (rolling) return;

        const prediction = selectedPrediction();
        if (!prediction) {
            toast('Pick a number first.', 'error');
            return;
        }
        const stake = parseInt(stakeInput.value, 10);
        if (!stake || stake <= 0) {
            toast('Enter a positive stake.', 'error');
            return;
        }

        rollBtn.disabled = true;
        const intervalId = startRollAnimation();
        const requestStart = Date.now();

        if (!postJson) {
            stopRollAnimation(intervalId);
            rollBtn.disabled = false;
            toast('API helper missing — refresh the page.', 'error');
            return;
        }

        postJson('/casino/' + guildId + '/dice/roll', { prediction: prediction, stake: stake })
            .then(function (body) {
                const elapsed = Date.now() - requestStart;
                const remaining = Math.max(0, ROLL_DURATION_MS - elapsed);
                setTimeout(function () {
                    stopRollAnimation(intervalId, body && body.landed);
                    rollBtn.disabled = false;
                    if (body && body.ok) {
                        showResult(body);
                        applyBalance(body.newBalance);
                    } else {
                        if (resultEl) resultEl.hidden = true;
                        toast((body && body.error) || 'Roll failed.', 'error');
                    }
                }, remaining);
            })
            .catch(function () {
                stopRollAnimation(intervalId);
                rollBtn.disabled = false;
                if (resultEl) resultEl.hidden = true;
                toast('Network error.', 'error');
            });
    });
})();
