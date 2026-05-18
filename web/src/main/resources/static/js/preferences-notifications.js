// Wire the notification preference matrix toggles to the existing
// REST endpoint:
//   POST /api/engagement/{guildId}/notifications/{kindCode}/{surfaceCode}
//   body: { optIn: boolean }
// Uses TobyApi.postJson which carries the Spring CSRF token from the
// fragment's meta tags. On 200, flips the cell class + label in place;
// on non-200, reverts so the UI never lies about the persisted state.
(function () {
    'use strict';

    document.addEventListener('DOMContentLoaded', function () {
        var main = document.querySelector('main[data-guild-id]');
        if (!main) return;
        var guildId = main.getAttribute('data-guild-id');

        var toggles = document.querySelectorAll('.notif-toggle');
        toggles.forEach(function (btn) {
            btn.addEventListener('click', function () {
                if (btn.disabled) return;
                var kind = btn.getAttribute('data-kind');
                var surface = btn.getAttribute('data-surface');
                var current = btn.getAttribute('data-opt-in') === 'true';
                var next = !current;

                // Optimistic update — the cell flips immediately. We
                // revert on failure so the UI stays truthful.
                btn.disabled = true;
                applyState(btn, next);

                window.TobyApi.postJson(
                    '/api/engagement/' + guildId + '/notifications/' + kind + '/' + surface,
                    { optIn: next }
                ).then(function (resp) {
                    btn.disabled = false;
                    if (!resp || resp.ok === false) {
                        // Revert.
                        applyState(btn, current);
                    }
                }).catch(function () {
                    btn.disabled = false;
                    applyState(btn, current);
                });
            });
        });

        function applyState(btn, optIn) {
            btn.setAttribute('data-opt-in', String(optIn));
            btn.classList.toggle('is-on', optIn);
            btn.classList.toggle('is-off', !optIn);
            btn.textContent = optIn ? 'On' : 'Off';
        }
    });
})();
