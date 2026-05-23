// Welcome tab: auto-role binding CRUD. Welcome / goodbye scalar
// settings (enabled / channel / message) use the generic .config-row
// save handler wired in moderationCommon.js; only the auto-role list
// needs page-specific code, mirroring leveling.js's role-reward shape.
(function () {
    'use strict';

    const ctx = window.ModerationCommon;
    if (!ctx) return;
    const { guildId, postJson } = ctx;

    // --- Add auto-role -----------------------------------------------------
    const addForm = document.getElementById('welcome-autorole-add');
    if (addForm) {
        addForm.addEventListener('submit', (e) => {
            e.preventDefault();
            const roleSelect = addForm.querySelector('select[name="roleId"]');
            const submitBtn = addForm.querySelector('button[type="submit"]');
            const roleId = (roleSelect.value || '').trim();
            if (!roleId) {
                toast('Pick a role.', 'error');
                return;
            }
            submitBtn.disabled = true;
            postJson('/moderation/' + guildId + '/auto-role', { roleId: roleId })
                .then(r => {
                    submitBtn.disabled = false;
                    if (r && r.ok) {
                        toast('Auto-role added. Reloading…', 'success');
                        setTimeout(() => window.location.reload(), 600);
                    } else {
                        toast(r?.error || 'Could not add auto-role.', 'error');
                    }
                })
                .catch(() => {
                    submitBtn.disabled = false;
                    toast('Network error.', 'error');
                });
        });
    }

    // --- Delete auto-role --------------------------------------------------
    // fetch() is used directly because postJson() only does POSTs; the
    // delete endpoint is a DELETE for REST symmetry with the add POST.
    function deleteAutoRole(roleId, btn) {
        if (!confirm('Stop auto-assigning this role?')) return;
        btn.disabled = true;
        fetch('/moderation/' + guildId + '/auto-role/' + roleId, {
            method: 'DELETE',
            headers: { 'Accept': 'application/json' },
            credentials: 'same-origin',
        }).then(r => r.json().catch(() => ({ ok: false, error: 'Bad response.' })))
          .then(r => {
              btn.disabled = false;
              if (r && r.ok) {
                  toast('Auto-role removed. Reloading…', 'success');
                  setTimeout(() => window.location.reload(), 600);
              } else {
                  toast(r?.error || 'Could not remove auto-role.', 'error');
              }
          })
          .catch(() => {
              btn.disabled = false;
              toast('Network error.', 'error');
          });
    }

    document.querySelectorAll('.welcome-autorole-delete').forEach(btn => {
        btn.addEventListener('click', () => {
            const roleId = (btn.dataset.roleId || '').trim();
            if (!roleId) return;
            deleteAutoRole(roleId, btn);
        });
    });
})();
