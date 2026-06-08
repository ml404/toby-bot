// Vanilla HTML5 drag-and-drop reorder for the music queue. The list element
// receives an `onReorder(from, to)` callback that the music-player.js wires
// to POST /queue/reorder. We render optimistically and let the server-side
// queueChanged SSE event reconcile back to truth.
(function () {
    'use strict';

    // `itemSelector` lets the same drag logic drive the live queue
    // (`li.queue-item`) and the playlist-editor track rows (`li.pl-track`).
    function attach(listEl, onReorder, itemSelector) {
        const selector = itemSelector || 'li.queue-item';
        // Re-binding cleanly each time the list is rebuilt.
        if (listEl.dataset.dndBound === '1') return;
        listEl.dataset.dndBound = '1';

        let draggingIdx = null;

        listEl.addEventListener('dragstart', (e) => {
            const li = e.target.closest(selector);
            if (!li) return;
            draggingIdx = Number(li.dataset.index);
            li.classList.add('dragging');
            if (e.dataTransfer) {
                e.dataTransfer.effectAllowed = 'move';
                // Some browsers require a non-empty payload to actually fire `drop`.
                e.dataTransfer.setData('text/plain', String(draggingIdx));
            }
        });

        listEl.addEventListener('dragover', (e) => {
            if (draggingIdx == null) return;
            e.preventDefault();
            if (e.dataTransfer) e.dataTransfer.dropEffect = 'move';
            const li = e.target.closest(selector);
            if (li) li.classList.add('drop-target');
        });

        listEl.addEventListener('dragleave', (e) => {
            const li = e.target.closest(selector);
            if (li) li.classList.remove('drop-target');
        });

        listEl.addEventListener('drop', (e) => {
            e.preventDefault();
            if (draggingIdx == null) return;
            const li = e.target.closest(selector);
            if (!li) return;
            const toIdx = Number(li.dataset.index);
            if (toIdx === draggingIdx) return;
            // Optimistic DOM swap: move the dragged node before/after the drop target.
            const allItems = Array.from(listEl.querySelectorAll(selector));
            const dragged = allItems[draggingIdx];
            const target = allItems[toIdx];
            if (dragged && target) {
                if (toIdx > draggingIdx) {
                    target.parentNode.insertBefore(dragged, target.nextSibling);
                } else {
                    target.parentNode.insertBefore(dragged, target);
                }
            }
            if (typeof onReorder === 'function') onReorder(draggingIdx, toIdx);
        });

        listEl.addEventListener('dragend', () => {
            draggingIdx = null;
            listEl.querySelectorAll('.dragging').forEach((n) => n.classList.remove('dragging'));
            listEl.querySelectorAll('.drop-target').forEach((n) => n.classList.remove('drop-target'));
        });
    }

    window.TobyMusicQueue = { attach: attach };
})();
