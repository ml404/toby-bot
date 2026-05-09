// Shared boilerplate + cross-cutting handlers for every per-tab moderation
// page (users, settings, voice, poll, casino, lottery). Each page's main
// element carries data-guild-id / data-actor-id / data-is-owner; this
// script grabs them once, exposes them on window.ModerationCommon for the
// per-tab scripts to consume, and wires up two handlers shared across
// pages:
//   - generic .config-row save (settings + lottery both have rows)
//   - .config-create-channel-form (leaderboard channel + lottery channel)
// Page-specific behaviour (e.g. user-search, jackpot reset) lives in
// /js/moderation/<tab>.js, loaded after this file.
(function () {
    'use strict';

    const main = document.querySelector('main[data-guild-id]');
    if (!main) return;

    const ctx = {
        main: main,
        guildId: main.dataset.guildId,
        isOwner: main.dataset.isOwner === 'true',
        actorId: main.dataset.actorId,
        postJson: window.TobyApi.postJson,
    };
    window.ModerationCommon = ctx;

    // --- Generic config-row save (settings + lottery share .config-row) ---
    document.querySelectorAll('.config-row').forEach(row => {
        const key = row.dataset.key;
        const input = row.querySelector('input, select');
        const btn = row.querySelector('.save-config');
        if (!btn || !input) return;
        btn.addEventListener('click', () => {
            const value = (input.value || '').toString();
            console.debug('[config save]', { key: key, value: value });
            btn.disabled = true;
            ctx.postJson('/moderation/' + ctx.guildId + '/config', { key: key, value: value })
                .then(r => {
                    btn.disabled = false;
                    console.debug('[config save response]', { key: key, response: r });
                    if (r && r.ok) {
                        toast('Config saved.', 'success');
                        // Reflect the saved value as the new default so a refresh
                        // doesn't look like "nothing happened" even if the read path
                        // is cached stale somewhere.
                        if (input.tagName === 'SELECT') {
                            Array.from(input.options).forEach(o => o.defaultSelected = (o.value === value));
                        } else {
                            input.defaultValue = value;
                        }
                    } else {
                        toast(r?.error || 'Could not save config.', 'error');
                    }
                })
                .catch((err) => {
                    btn.disabled = false;
                    console.error('[config save error]', { key: key, err: err });
                    toast('Network error.', 'error');
                });
        });
    });

    // --- Generic "Create channel" form handler, parameterised by
    //     visibility. Two flows share the same form-field shape (name +
    //     category + new-category) and lifecycle (POST → toast →
    //     reload-page-so-the-new-channel-appears-in-the-dropdown);
    //     they differ only on the endpoint and the confirm-prompt
    //     wording. Wire each flavour to its CSS class:
    //       - .config-create-channel-form       → public read-only
    //                                               (LEADERBOARD_CHANNEL,
    //                                                LOTTERY_CHANNEL).
    //       - .config-create-admin-channel-form → admin-only/private
    //                                               (CASINO_MODLOG_CHANNEL_ID).
    function wireCreateChannelForm(selector, opts) {
        document.querySelectorAll(selector).forEach(form => {
            const targetConfig = form.dataset.targetConfig;
            const nameInput = form.querySelector('input[name="name"]');
            const parentSelect = form.querySelector('select[name="parentCategoryId"]');
            const newCategoryInput = form.querySelector('input[name="newCategoryName"]');
            const btn = form.querySelector('.config-create-channel-btn');
            if (!targetConfig || !nameInput || !btn) return;
            form.addEventListener('submit', (e) => {
                e.preventDefault();
                const name = (nameInput.value || '').trim();
                if (!name) {
                    toast('Channel name required.', 'error');
                    return;
                }
                const parentCategoryId = (parentSelect?.value || '').trim();
                const newCategoryName = (newCategoryInput?.value || '').trim();
                // Build a confirm-message tail that reflects what'll
                // happen server-side. The new-category path takes
                // precedence over the dropdown so the user isn't
                // surprised by their own input.
                let categoryNote = '';
                if (newCategoryName) {
                    categoryNote = ' under a new category "' + newCategoryName + '"';
                } else if (parentCategoryId) {
                    const parentText = parentSelect.options[parentSelect.selectedIndex]?.text;
                    if (parentText) categoryNote = ' under category "' + parentText + '"';
                }
                if (!confirm(
                    'Create a new "#' + name + '" channel' + categoryNote + '? ' +
                    opts.confirmDescription + ' ' +
                    'The ' + targetConfig + ' config will be set to this new channel.'
                )) return;
                btn.disabled = true;
                ctx.postJson('/moderation/' + ctx.guildId + opts.endpoint, {
                    name: name,
                    targetConfig: targetConfig,
                    parentCategoryId: parentCategoryId || null,
                    newCategoryName: newCategoryName || null
                }).then(r => {
                    btn.disabled = false;
                    if (r && r.ok) {
                        toast('Created #' + r.channelName + '. Reloading…', 'success');
                        setTimeout(() => window.location.reload(), 700);
                    } else {
                        toast(r?.error || 'Could not create channel.', 'error');
                    }
                }).catch(() => {
                    btn.disabled = false;
                    toast('Network error.', 'error');
                });
            });
        });
    }

    wireCreateChannelForm('.config-create-channel-form', {
        endpoint: '/channel/create-read-only',
        confirmDescription:
            'It will be read-only for @everyone and post-only for TobyBot.',
    });
    wireCreateChannelForm('.config-create-admin-channel-form', {
        endpoint: '/channel/create-admin-only',
        confirmDescription:
            'Only TobyBot and server admins (Administrator role) will be able to read or post in it.',
    });
})();
