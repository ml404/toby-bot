// Leveling tab: role-reward CRUD + per-title required-level save.
// Generic config-row save + channel-create handlers live in moderationCommon.js.
(function () {
    'use strict';

    const ctx = window.ModerationCommon;
    if (!ctx) return;
    const { guildId, postJson } = ctx;

    // --- Add role reward ---------------------------------------------------
    const addForm = document.getElementById('leveling-reward-add');
    if (addForm) {
        addForm.addEventListener('submit', (e) => {
            e.preventDefault();
            const levelInput = addForm.querySelector('input[name="level"]');
            const roleSelect = addForm.querySelector('select[name="roleId"]');
            const submitBtn = addForm.querySelector('button[type="submit"]');
            const level = parseInt((levelInput.value || '').trim(), 10);
            const roleId = (roleSelect.value || '').trim();
            if (!level || level < 1) {
                toast('Level must be 1 or higher.', 'error');
                return;
            }
            if (!roleId) {
                toast('Pick a role.', 'error');
                return;
            }
            submitBtn.disabled = true;
            postJson('/moderation/' + guildId + '/level-reward', { level: level, roleId: roleId })
                .then(r => {
                    submitBtn.disabled = false;
                    if (r && r.ok) {
                        toast('Role reward saved. Reloading…', 'success');
                        setTimeout(() => window.location.reload(), 600);
                    } else {
                        toast(r?.error || 'Could not save reward.', 'error');
                    }
                })
                .catch(() => {
                    submitBtn.disabled = false;
                    toast('Network error.', 'error');
                });
        });
    }

    // --- Delete role reward ------------------------------------------------
    // fetch() is used directly here because postJson() only does POSTs;
    // the delete endpoint is a DELETE for REST symmetry with the upsert
    // POST. Keeps the same { ok, error } response shape.
    function deleteReward(level, btn) {
        if (!confirm('Delete the role reward bound to level ' + level + '?')) return;
        btn.disabled = true;
        fetch('/moderation/' + guildId + '/level-reward/' + level, {
            method: 'DELETE',
            headers: { 'Accept': 'application/json' },
            credentials: 'same-origin',
        }).then(r => r.json().catch(() => ({ ok: false, error: 'Bad response.' })))
          .then(r => {
              btn.disabled = false;
              if (r && r.ok) {
                  toast('Reward removed. Reloading…', 'success');
                  setTimeout(() => window.location.reload(), 600);
              } else {
                  toast(r?.error || 'Could not delete reward.', 'error');
              }
          })
          .catch(() => {
              btn.disabled = false;
              toast('Network error.', 'error');
          });
    }

    document.querySelectorAll('.leveling-reward-delete').forEach(btn => {
        btn.addEventListener('click', () => {
            const level = parseInt(btn.dataset.level || '0', 10);
            if (!level) return;
            deleteReward(level, btn);
        });
    });

    // --- Save title required_level ----------------------------------------
    document.querySelectorAll('.leveling-title-save').forEach(btn => {
        btn.addEventListener('click', () => {
            const row = btn.closest('tr');
            const titleId = btn.dataset.titleId;
            const input = row?.querySelector('.leveling-title-level-input');
            if (!titleId || !input) return;
            const requiredLevel = parseInt((input.value || '0').trim(), 10);
            if (Number.isNaN(requiredLevel) || requiredLevel < 0) {
                toast('Required level must be zero or higher.', 'error');
                return;
            }
            btn.disabled = true;
            postJson('/moderation/' + guildId + '/title/' + titleId + '/required-level',
                { requiredLevel: requiredLevel })
                .then(r => {
                    btn.disabled = false;
                    if (r && r.ok) {
                        input.defaultValue = String(requiredLevel);
                        toast('Title gate saved.', 'success');
                    } else {
                        toast(r?.error || 'Could not save title gate.', 'error');
                    }
                })
                .catch(() => {
                    btn.disabled = false;
                    toast('Network error.', 'error');
                });
        });
    });
})();
