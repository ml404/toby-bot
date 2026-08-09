// Magic toolkit page (/magic). A tabbed hub — cube building (generate / preview
// / as-fan / compare), card tools (lookup / legality / price watch) and
// reference (sets / keywords) — over the /magic/api/* JSON endpoints. The
// emphasis is on showing the actual CARDS: generated packs and the preview
// both list real card names linked to Scryfall.
//
// Layer 3 of 3, and the page's entry point: the event wiring, the fetches and
// the per-tab state. Nothing here runs outside a browser except via the
// exports at the bottom, which re-publish all three layers as `TobyCube` so
// the Jest suite (and anything else) sees one surface.
//
//   magic-format.js  pure helpers — no DOM, no network
//   magic-render.js  response -> DOM, no state
//   magic.js         this file: wiring, fetching, state
(function (root) {
    'use strict';

    // The layers below: `require` under Jest, the shared global in the
    // browser (the page loads the three files in dependency order).
    const fmt = (typeof module !== 'undefined' && module.exports)
        ? require('./magic-format.js')
        : root.TobyCubeFormat;

    const rnd = (typeof module !== 'undefined' && module.exports)
        ? require('./magic-render.js')
        : root.TobyCubeRender;

    // Hoisted into scope so the wiring below reads exactly as it did when
    // this all lived in one file.
    const AUTO_SEARCH_DEBOUNCE_MS = fmt.AUTO_SEARCH_DEBOUNCE_MS;
    const AUTO_SEARCH_MIN = fmt.AUTO_SEARCH_MIN;
    const DEFAULT_TAB = fmt.DEFAULT_TAB;
    const LISTS_API = fmt.LISTS_API;
    const MAX_CARD_SUGGEST = fmt.MAX_CARD_SUGGEST;
    const NO_SHARED_SOURCE = fmt.NO_SHARED_SOURCE;
    const TABS = fmt.TABS;
    const WATCHES_API = fmt.WATCHES_API;
    const absoluteUrl = fmt.absoluteUrl;
    const applyCardChoice = fmt.applyCardChoice;
    const asfanSentence = fmt.asfanSentence;
    const asfanUrl = fmt.asfanUrl;
    const cardCountLabel = fmt.cardCountLabel;
    const cardStatline = fmt.cardStatline;
    const cardUrl = fmt.cardUrl;
    const categoryColor = fmt.categoryColor;
    const combosUrl = fmt.combosUrl;
    const countCards = fmt.countCards;
    const cubeListText = fmt.cubeListText;
    const currentLineInfo = fmt.currentLineInfo;
    const dealPackUrl = fmt.dealPackUrl;
    const deleteListUrl = fmt.deleteListUrl;
    const formatAsFan = fmt.formatAsFan;
    const formatMoney = fmt.formatMoney;
    const generateUrl = fmt.generateUrl;
    const groupsToList = fmt.groupsToList;
    const importFileError = fmt.importFileError;
    const legalityUrl = fmt.legalityUrl;
    const manaSymbolUrls = fmt.manaSymbolUrls;
    const notFoundText = fmt.notFoundText;
    const packHeader = fmt.packHeader;
    const packImportRequest = fmt.packImportRequest;
    const packsSummary = fmt.packsSummary;
    const packsToText = fmt.packsToText;
    const previewUrl = fmt.previewUrl;
    const priceLine = fmt.priceLine;
    const provenanceLine = fmt.provenanceLine;
    const queryShareUrl = fmt.queryShareUrl;
    const readUrlPrefill = fmt.readUrlPrefill;
    const ruleUrl = fmt.ruleUrl;
    const rulingsUrl = fmt.rulingsUrl;
    const samplePackRequest = fmt.samplePackRequest;
    const scryfallAutocompleteUrl = fmt.scryfallAutocompleteUrl;
    const scryfallCardUrl = fmt.scryfallCardUrl;
    const searchSentence = fmt.searchSentence;
    const searchUrl = fmt.searchUrl;
    const setUrl = fmt.setUrl;
    const shareDealRequest = fmt.shareDealRequest;
    const splitQuantityPrefix = fmt.splitQuantityPrefix;
    const tabIdFromHash = fmt.tabIdFromHash;
    const today = fmt.today;
    const totalValueText = fmt.totalValueText;
    const watchLine = fmt.watchLine;
    const zoomPosition = fmt.zoomPosition;

    // Hoisted from the render layer, as above.
    const applyPackShape = rnd.applyPackShape;
    const cardDetailModal = rnd.cardDetailModal;
    const copyToClipboard = rnd.copyToClipboard;
    const downloadText = rnd.downloadText;
    const hide = rnd.hide;
    const prefersTap = rnd.prefersTap;
    const readFileText = rnd.readFileText;
    const renderAnalytics = rnd.renderAnalytics;
    const renderCardLookup = rnd.renderCardLookup;
    const renderColorPairs = rnd.renderColorPairs;
    const renderColorPips = rnd.renderColorPips;
    const renderCombos = rnd.renderCombos;
    const renderDiff = rnd.renderDiff;
    const renderDistribution = rnd.renderDistribution;
    const renderDuplicates = rnd.renderDuplicates;
    const renderGroups = rnd.renderGroups;
    const renderLegality = rnd.renderLegality;
    const renderManaCurve = rnd.renderManaCurve;
    const renderNotFound = rnd.renderNotFound;
    const renderPacks = rnd.renderPacks;
    const renderRarity = rnd.renderRarity;
    const renderRule = rnd.renderRule;
    const renderRulings = rnd.renderRulings;
    const renderSearchResults = rnd.renderSearchResults;
    const renderSet = rnd.renderSet;
    const renderTotalValue = rnd.renderTotalValue;
    const renderTypeBreakdown = rnd.renderTypeBreakdown;
    const renderValueExtremes = rnd.renderValueExtremes;
    const renderWatches = rnd.renderWatches;
    const setStatus = rnd.setStatus;
    const show = rnd.show;

    // --- Tabs, panels and the shared JSON helpers ----------------------

    function q(doc, sel) { return doc.querySelector(sel); }
    function statusFor(doc, name) { return q(doc, '[data-status="' + name + '"]'); }
    function emptyFor(doc, name) { return q(doc, '[data-empty="' + name + '"]'); }

    function activateTab(doc, name) {
        if (TABS.indexOf(name) < 0) return;
        doc.querySelectorAll('[role="tab"][data-tab]').forEach(function (tab) {
            const selected = tab.getAttribute('data-tab') === name;
            tab.setAttribute('aria-selected', selected ? 'true' : 'false');
            tab.tabIndex = selected ? 0 : -1;
        });
        doc.querySelectorAll('[data-panel]').forEach(function (panel) {
            panel.hidden = panel.getAttribute('data-panel') !== name;
        });
        // Some tools (as-fan, compare) work off their own inputs, so the shared
        // "Your cube" source (and its cue) don't apply — hide them there.
        const usesSource = NO_SHARED_SOURCE.indexOf(name) < 0;
        doc.querySelectorAll('[data-needs-cube]').forEach(function (el) {
            el.hidden = !usesSource;
        });
    }

    function wireTabs(doc) {
        const tabs = Array.prototype.slice.call(doc.querySelectorAll('[role="tab"][data-tab]'));
        if (!tabs.length) return;
        tabs.forEach(function (tab, i) {
            tab.addEventListener('click', function () {
                const name = tab.getAttribute('data-tab');
                activateTab(doc, name);
                if (root.history && root.history.replaceState) {
                    root.history.replaceState(null, '', '#' + name);
                }
            });
            tab.addEventListener('keydown', function (e) {
                let next = -1;
                if (e.key === 'ArrowRight') next = (i + 1) % tabs.length;
                else if (e.key === 'ArrowLeft') next = (i - 1 + tabs.length) % tabs.length;
                if (next >= 0) {
                    e.preventDefault();
                    tabs[next].focus();
                    tabs[next].click();
                }
            });
        });
        // Normalise the initial state: a #hash deep-link wins, else the default
        // tab — this also hides the cube-only scaffolding when defaulting to card.
        const fromHash = tabIdFromHash(root.location && root.location.hash);
        activateTab(doc, fromHash || DEFAULT_TAB);
    }

    /**
     * Re-activate the matching tab when only the URL hash changes — i.e. a
     * deep-link like /magic#preview clicked while already on /magic (e.g. from
     * the navbar dropdown), where no reload fires wireTabs again. Registered
     * unconditionally so it's live even if wireTabs bailed for lack of tabs.
     */
    function wireHashChange(doc) {
        if (!root || !root.addEventListener) return;
        root.addEventListener('hashchange', function () {
            const name = tabIdFromHash(root.location && root.location.hash);
            if (name) activateTab(doc, name);
        });
    }

    function wireExamples(doc) {
        doc.querySelectorAll('[data-example]').forEach(function (chip) {
            chip.addEventListener('click', function () {
                const value = chip.getAttribute('data-example');
                doc.querySelectorAll('input[name="query"]').forEach(function (input) {
                    input.value = value;
                });
            });
        });
    }

    function getJson(url) {
        return fetch(url).then(function (r) { return r.json(); });
    }

    function postJson(url, body) {
        return fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body),
        }).then(function (r) { return r.json(); });
    }

    function setSpinner(doc, name, on) {
        const el = doc.querySelector('[data-spinner="' + name + '"]');
        if (el) el.hidden = !on;
    }

    /** Live "N cards" counter + a clear button for the paste box. */
    function wireCardCount(doc) {
        const textarea = doc.querySelector('textarea[name="list"]');
        const count = doc.querySelector('[data-card-count]');
        if (!textarea || !count) return;
        function update() {
            const n = countCards(textarea.value);
            count.textContent = n + (n === 1 ? ' card' : ' cards');
        }
        textarea.addEventListener('input', update);
        update(); // initial (a shared/prefilled list arrives populated)
        const clear = doc.querySelector('[data-clear-list]');
        if (clear) clear.addEventListener('click', function () {
            textarea.value = '';
            update();
            textarea.focus();
        });
    }

    /** "Copy link" for the current Scryfall search → /magic?q=... */
    function wireCopyQuery(doc) {
        const btn = doc.querySelector('[data-copy-query]');
        const input = doc.querySelector('[data-source-panel="search"] input[name="query"]');
        if (!btn || !input) return;
        const label = btn.textContent;
        btn.addEventListener('click', function () {
            const q = (input.value || '').trim();
            if (!q) { btn.textContent = 'Enter a search first'; root.setTimeout(function () { btn.textContent = label; }, 1500); return; }
            const url = queryShareUrl(root.location && root.location.origin, q);
            if (root.navigator && root.navigator.clipboard) {
                root.navigator.clipboard.writeText(url).then(
                    function () { btn.textContent = '✓ Link copied'; root.setTimeout(function () { btn.textContent = label; }, 2000); },
                    function () { btn.textContent = url; }
                );
            } else {
                btn.textContent = url;
            }
        });
    }

    /**
     * Pre-loads the source from ?q= / ?list= so a shared link lands ready.
     * A prefill targets the shared "Your cube" source, which only shows on the
     * cube tabs — but the page defaults to Card lookup, where it's hidden. So a
     * prefill also lands on a cube tab (Preview) to reveal it, unless an
     * explicit #hash deep-link already chose one.
     */
    function prefillFromUrl(doc) {
        const pre = readUrlPrefill(root.location && root.location.search);
        const hasPrefill = pre.q != null || pre.list != null;
        if (pre.q != null) {
            const input = doc.querySelector('[data-source-panel="search"] input[name="query"]');
            if (input) input.value = pre.q;
            setSource(doc, 'search');
        } else if (pre.list != null) {
            const textarea = doc.querySelector('textarea[name="list"]');
            if (textarea) textarea.value = pre.list;
            setSource(doc, 'list');
        }
        if (hasPrefill && !tabIdFromHash(root.location && root.location.hash)) {
            activateTab(doc, 'preview');
        }
    }

    /**
     * A server-rendered shared cube (/magic/c/<token>) arrives pre-loaded in
     * the list box, or — for a bad token — with a "not found" notice there.
     * Either lives in the shared "Your cube" source, which is hidden on the
     * default Card lookup tab, so switch to the list source and a cube tab
     * (Preview) to reveal it. Returns true when a shared cube/miss was present.
     */
    function revealSharedCube(doc) {
        if (!doc.querySelector('[data-shared-banner], [data-shared-missing]')) return false;
        setSource(doc, 'list');
        activateTab(doc, 'preview');
        return true;
    }

    /** Disables the form's submit button while a request is in flight. */
    function withBusy(form, busy) {
        const btn = form.querySelector('button[type="submit"]');
        if (btn) btn.disabled = busy;
    }

    /**
     * What to call a shared deal: the saved-cube name if the user has one
     * typed, else the Scryfall query it was dealt from, else nothing (the
     * server falls back to "Shared deal"). Pure so it's unit-testable.
     */
    function dealShareName(doc) {
        const saved = q(doc, '[data-saved-lists]');
        const named = ((saved && saved.value) || '').trim();
        if (named) return named + ' — packs';
        const query = q(doc, '[data-source-panel="search"] input[name="query"]');
        const q1 = ((query && query.value) || '').trim();
        return q1 ? q1 + ' — packs' : '';
    }

    // --- Card source (Scryfall search vs pasted list) ------------------

    /** Which source is active and its value. */
    function activeSource(doc) {
        const listTab = doc.querySelector('[data-source-tab="list"]');
        if (listTab && listTab.getAttribute('aria-selected') === 'true') {
            const ta = doc.querySelector('[data-source-panel="list"] textarea[name="list"]');
            return { mode: 'list', list: ((ta && ta.value) || '').trim() };
        }
        const input = doc.querySelector('[data-source-panel="search"] input[name="query"]');
        return { mode: 'search', query: ((input && input.value) || '').trim() };
    }

    function setSource(doc, mode) {
        doc.querySelectorAll('[data-source-tab]').forEach(function (b) {
            b.setAttribute('aria-selected', b.getAttribute('data-source-tab') === mode ? 'true' : 'false');
        });
        doc.querySelectorAll('[data-source-panel]').forEach(function (p) {
            p.hidden = p.getAttribute('data-source-panel') !== mode;
        });
    }

    function wireSource(doc) {
        doc.querySelectorAll('[data-source-tab]').forEach(function (btn) {
            btn.addEventListener('click', function () { setSource(doc, btn.getAttribute('data-source-tab')); });
        });
    }

    /** Repopulates the saved-cube datalist(s) from the cached {name: cards} map. */
    function fillSavedOptions(doc, cache) {
        const names = Object.keys(cache).sort();
        doc.querySelectorAll('[data-saved-options]').forEach(function (dl) {
            dl.replaceChildren();
            names.forEach(function (name) {
                const opt = document.createElement('option');
                opt.value = name;
                dl.appendChild(opt);
            });
        });
    }

    /**
     * Account-bound saved lists: persisted server-side against the logged-in
     * Discord user via `/magic/api/lists`, so they follow the user across
     * devices. The saved-cube picker is a datalist-backed typeahead — type to
     * filter your cube names, pick one to load it. The controls only exist in
     * the DOM for a logged-in user (see magic.html), so this no-ops otherwise.
     */
    function wireSavedLists(doc) {
        const textarea = doc.querySelector('textarea[name="list"]');
        const input = doc.querySelector('[data-saved-lists]');
        if (!textarea || !input) return;
        const saveBtn = doc.querySelector('[data-save-list]');
        const delBtn = doc.querySelector('[data-delete-list]');
        const status = doc.querySelector('[data-saved-status]');
        let cache = {};

        function reload() {
            return getJson(LISTS_API)
                .then(function (rows) {
                    cache = {};
                    (rows || []).forEach(function (row) { cache[row.name] = row.cards; });
                    fillSavedOptions(doc, cache);
                })
                .catch(function () { /* leave options as-is on a transient error */ });
        }

        reload();

        // Typeahead: picking (or typing) a known saved name loads that cube.
        input.addEventListener('change', function () {
            const name = (input.value || '').trim();
            if (cache[name] == null) return;
            textarea.value = cache[name];
            setSource(doc, 'list');
            setStatus(status, 'Loaded “' + name + '”.');
        });

        if (saveBtn) saveBtn.addEventListener('click', function () {
            const text = (textarea.value || '').trim();
            if (!text) { setStatus(status, 'Nothing to save — paste a list first.'); return; }
            // Default the prompt to the name in the picker so editing and
            // re-saving overwrites it in one step (Enter); a new name forks it.
            const suggested = (input.value || '').trim();
            const name = root.prompt ? root.prompt('Name this cube list (same name overwrites):', suggested) : null;
            if (!name || !name.trim()) return;
            setStatus(status, 'Saving…');
            fetch(LISTS_API, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name: name.trim(), cards: text }),
            }).then(function (r) {
                if (r.status === 409) { setStatus(status, 'You\'ve hit the saved-list limit (50).'); return null; }
                if (!r.ok) { setStatus(status, 'Couldn\'t save. Are you still logged in?'); return null; }
                return r.json();
            }).then(function (saved) {
                if (!saved) return;
                setStatus(status, 'Saved “' + saved.name + '”.');
                reload().then(function () { input.value = saved.name; });
            }).catch(function () { setStatus(status, 'Couldn\'t save. Try again.'); });
        });

        if (delBtn) delBtn.addEventListener('click', function () {
            const name = (input.value || '').trim();
            if (!name) return;
            setStatus(status, 'Deleting…');
            fetch(deleteListUrl(name), { method: 'DELETE' })
                .then(function () { setStatus(status, 'Deleted “' + name + '”.'); input.value = ''; return reload(); })
                .catch(function () { setStatus(status, 'Couldn\'t delete. Try again.'); });
        });
    }

    /**
     * Publishes the current list as a public, immutable snapshot (logged-in
     * only) and shows the resulting `/magic/c/<token>` link, copying it to the
     * clipboard. The Share controls only exist in the DOM for a logged-in user.
     */
    function wireShare(doc) {
        const textarea = doc.querySelector('textarea[name="list"]');
        const shareBtn = doc.querySelector('[data-share-list]');
        if (!textarea || !shareBtn) return;
        const result = doc.querySelector('[data-share-result]');
        const urlInput = doc.querySelector('[data-share-url]');
        const copyBtn = doc.querySelector('[data-share-copy]');
        const status = doc.querySelector('[data-saved-status]');
        const nameInput = doc.querySelector('[data-saved-lists]');

        function copy(text) {
            if (root.navigator && root.navigator.clipboard) {
                root.navigator.clipboard.writeText(text).then(
                    function () { setStatus(status, 'Share link copied to clipboard.'); },
                    function () { /* clipboard blocked — the field is selectable */ }
                );
            }
        }

        shareBtn.addEventListener('click', function () {
            const text = (textarea.value || '').trim();
            if (!text) { setStatus(status, 'Nothing to share — paste a list first.'); return; }
            const name = ((nameInput && nameInput.value) || '').trim();
            setStatus(status, 'Creating link…');
            fetch('/magic/api/share', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name: name, cards: text }),
            }).then(function (r) {
                if (r.status === 401) { setStatus(status, 'Log in to create a share link.'); return null; }
                if (!r.ok) { setStatus(status, 'Couldn\'t create a link. Try again.'); return null; }
                return r.json();
            }).then(function (json) {
                if (!json) return;
                const full = absoluteUrl(root.location && root.location.origin, json.url);
                if (urlInput) urlInput.value = full;
                if (result) result.hidden = false;
                setStatus(status, 'Share link ready.');
                copy(full);
            }).catch(function () { setStatus(status, 'Couldn\'t create a link. Try again.'); });
        });

        if (copyBtn && urlInput) copyBtn.addEventListener('click', function () {
            urlInput.select();
            copy(urlInput.value);
        });
    }

    /**
     * Wires every card-input on the page to Scryfall's autocomplete API.
     * Each input/textarea sits inside a [.cube-list-wrap] with a sibling
     * [data-card-suggest] listbox; this finds those pairs and wires them.
     */
    function wireCardAutocomplete(doc) {
        doc.querySelectorAll('[data-card-suggest]').forEach(function (box) {
            const wrap = box.closest('.cube-list-wrap');
            const field = wrap && wrap.querySelector('textarea, input[type="text"]');
            if (field) wireCardAutocompletePair(field, box);
        });
    }

    /**
     * As the user types a card name in [field], suggests real card names from
     * Scryfall's autocomplete API and completes the current line on pick
     * (keeping any leading quantity for decklist textareas). Works for both
     * `<textarea>` decklists and single-line `<input>` card-name fields.
     * Arrow keys + Enter/Escape, plus mouse.
     */
    function wireCardAutocompletePair(field, box) {
        let timer = null;
        let controller = null;
        let items = [];
        let active = -1;

        function close() { box.hidden = true; box.replaceChildren(); items = []; active = -1; }

        function setActive(i) {
            const lis = box.querySelectorAll('.cube-card-suggest-item');
            lis.forEach(function (el) { el.classList.remove('is-active'); });
            if (i < 0 || i >= lis.length) { active = -1; return; }
            active = i;
            lis[i].classList.add('is-active');
            lis[i].scrollIntoView({ block: 'nearest' });
        }

        function pick(name) {
            const applied = applyCardChoice(field.value, field.selectionStart || 0, name);
            field.value = applied.value;
            try { field.selectionStart = field.selectionEnd = applied.caret; } catch (e) { /* some input types reject setSelectionRange */ }
            close();
            field.focus();
            // Let listeners (card-count badge, etc.) react to the completion.
            field.dispatchEvent(new Event('input', { bubbles: true }));
        }

        function render(names) {
            box.replaceChildren();
            items = names;
            active = -1;
            if (!names.length) { close(); return; }
            names.forEach(function (name) {
                const li = document.createElement('li');
                li.className = 'cube-card-suggest-item';
                li.setAttribute('role', 'option');
                li.textContent = name;
                li.addEventListener('mousedown', function (e) { e.preventDefault(); pick(name); });
                box.appendChild(li);
            });
            box.hidden = false;
        }

        function suggest() {
            const info = currentLineInfo(field.value, field.selectionStart || 0);
            const partial = splitQuantityPrefix(info.text).name.trim();
            if (partial.length < 2) { close(); return; }
            if (controller) controller.abort();
            controller = (typeof AbortController !== 'undefined') ? new AbortController() : null;
            fetch(scryfallAutocompleteUrl(partial), controller ? { signal: controller.signal } : undefined)
                .then(function (r) { return r.ok ? r.json() : { data: [] }; })
                .then(function (json) { render((json.data || []).slice(0, MAX_CARD_SUGGEST)); })
                .catch(function () { /* aborted or offline — leave the box as it is */ });
        }

        field.addEventListener('input', function () {
            if (timer) clearTimeout(timer);
            timer = setTimeout(suggest, 160);
        });
        field.addEventListener('keydown', function (e) {
            if (box.hidden) return;
            if (e.key === 'ArrowDown') { e.preventDefault(); setActive(Math.min(active + 1, items.length - 1)); }
            else if (e.key === 'ArrowUp') { e.preventDefault(); setActive(Math.max(active - 1, 0)); }
            else if (e.key === 'Enter' && active >= 0) { e.preventDefault(); pick(items[active]); }
            else if (e.key === 'Escape') { close(); }
        });
        field.addEventListener('blur', function () { setTimeout(close, 150); });
    }

    function wireSearch(doc) {
        const form = q(doc, '[data-form="search"]');
        if (!form) return;
        const status = statusFor(doc, 'search');
        const result = q(doc, '[data-result="search"]');
        // The shared detail panel below the grid: a clicked tile — or a search
        // that narrows to exactly one card — opens its full details there,
        // reusing the panel's rulings/combos wiring (wireCardLookup).
        const cardResult = q(doc, '[data-result="card"]');
        const cardStatus = statusFor(doc, 'card');

        // Monotonic token for the detail panel: only the latest openDetail may
        // touch it, so a slow load can't overwrite (or a clear can't wipe) a
        // newer one's card.
        let detailSeq = 0;
        // The tile that opened the modal, so focus returns to it on dismiss.
        let lastTrigger = null;

        // Hides the detail panel and drops any pending/stale load. The visual
        // teardown, shared by an explicit dismiss and a fresh search.
        function hideDetailPanel() {
            detailSeq++;
            setStatus(cardStatus, '');
            hide(cardResult);
        }

        // The detail panel can be lifted into a dismissible overlay (for an
        // explicit click). Dismissing it tears the panel down and hands focus
        // back to the tile that opened it.
        const modal = cardDetailModal(doc, cardResult, function () {
            hideDetailPanel();
            if (lastTrigger && lastTrigger.focus) lastTrigger.focus();
        });

        // Loads a card's full details into the shared panel. `opts.modal` lifts
        // it into a centered overlay (an explicit click on a result); without
        // it the panel renders inline and `opts.scroll` brings it into view —
        // used when a one-match search auto-opens the detail under the grid.
        function openDetail(name, opts) {
            if (!name || !cardResult) return;
            const wantModal = !!(opts && opts.modal && modal);
            if (opts && opts.trigger) lastTrigger = opts.trigger;
            const token = ++detailSeq;
            setStatus(cardStatus, 'Loading ' + name + '…');
            hide(cardResult);
            getJson(cardUrl(name))
                .then(function (json) {
                    if (token !== detailSeq) return;
                    if (!json.ok) { setStatus(cardStatus, json.error || 'No card found.'); return; }
                    setStatus(cardStatus, '');
                    renderCardLookup(cardResult, json.card);
                    show(cardResult);
                    if (wantModal) {
                        modal.open();
                    } else if (opts && opts.scroll && cardResult.scrollIntoView) {
                        cardResult.scrollIntoView({ behavior: 'smooth', block: 'start' });
                    }
                })
                .catch(function () { if (token === detailSeq) setStatus(cardStatus, 'Something went wrong. Try again.'); });
        }

        // Closes the overlay (if open) and hides the panel — funnelled so a
        // dismiss and a fresh search share one teardown without double-firing.
        function clearDetail() {
            if (modal && modal.isOpen()) modal.close(); // its onClose hides the panel
            else hideDetailPanel();
        }

        // Delegated so it survives each grid re-render: a left-click on a result
        // tile opens that card's full details in a dismissible overlay instead
        // of leaving for Scryfall. Modifier/middle clicks fall through to the
        // tile's href (open Scryfall).
        result.addEventListener('click', function (e) {
            if (e.defaultPrevented || e.button === 1 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
            const tile = e.target.closest && e.target.closest('.cube-card');
            if (!tile || (e.target.closest && e.target.closest('.cube-card-flip'))) return;
            const name = tile.getAttribute('data-card-name') || tile.getAttribute('title');
            if (!name || !cardResult) return;
            e.preventDefault();
            openDetail(name, { modal: true, trigger: tile });
        });

        let timer = null;
        let controller = null;
        // Monotonic token: only the latest search may touch the UI, so a slow
        // earlier response can't overwrite a newer one's results.
        let seq = 0;

        function filtersOf() {
            const data = new FormData(form);
            return {
                name: (data.get('name') || '').trim(),
                type: (data.get('type') || '').trim(),
                query: (data.get('query') || '').trim(),
            };
        }

        function run(filters) {
            if (!filters.name && !filters.type && !filters.query) {
                setStatus(status, 'Enter a name, a type, or a Scryfall query to search.');
                hide(result);
                clearDetail();
                return;
            }
            // Supersede any in-flight request so its response is dropped.
            if (controller) controller.abort();
            controller = (typeof AbortController !== 'undefined') ? new AbortController() : null;
            const token = ++seq;
            setStatus(status, 'Searching…');
            withBusy(form, true);
            fetch(searchUrl(filters), controller ? { signal: controller.signal } : undefined)
                .then(function (r) { return r.json(); })
                .then(function (json) {
                    if (token !== seq) return;
                    if (!json.ok) { setStatus(status, json.error || 'No cards matched that search.'); hide(result); clearDetail(); return; }
                    setStatus(status, searchSentence(json.total, json.capped));
                    const cards = json.cards || [];
                    // A search that pins down a single card opens its full
                    // details straight away (no scroll — it sits under the
                    // grid), in place of a one-tile grid. Otherwise show the
                    // grid and drop any detail from a previous search.
                    if (cards.length === 1) {
                        hide(result);
                        openDetail(cards[0].name, { scroll: false });
                    } else {
                        clearDetail();
                        renderSearchResults(result, cards);
                        show(result);
                    }
                })
                .catch(function (e) {
                    if (e && e.name === 'AbortError') return; // superseded — leave the UI to the newer search
                    if (token === seq) { setStatus(status, 'Something went wrong. Try again.'); clearDetail(); }
                })
                .then(function () { if (token === seq) withBusy(form, false); });
        }

        // Manual submit runs immediately, cancelling any pending auto-search.
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            if (timer) { clearTimeout(timer); timer = null; }
            run(filtersOf());
        });

        // Autosearch: as the user types, debounce then fire — once a filter is
        // long enough to be meaningful, so a single stray letter doesn't pull
        // thousands of cards. Clearing every field clears the results.
        function scheduleAuto() {
            if (timer) clearTimeout(timer);
            timer = setTimeout(function () {
                timer = null;
                const f = filtersOf();
                if (!f.name && !f.type && !f.query) {
                    if (controller) controller.abort();
                    seq++; // drop any in-flight response
                    setStatus(status, '');
                    hide(result);
                    clearDetail();
                    return;
                }
                if (f.name.length >= AUTO_SEARCH_MIN || f.type.length >= AUTO_SEARCH_MIN || f.query.length >= AUTO_SEARCH_MIN) {
                    run(f);
                }
            }, AUTO_SEARCH_DEBOUNCE_MS);
        }
        form.querySelectorAll('input[name="name"], input[name="type"], input[name="query"]').forEach(function (input) {
            input.addEventListener('input', scheduleAuto);
        });
    }

    function wireCardLookup(doc) {
        // The detail panel is shared with the search grid (which opens it on a
        // tile click or a one-match search), so its rulings/combos handlers are
        // wired whether or not a standalone lookup form is present.
        const result = q(doc, '[data-result="card"]');
        if (!result) return;
        const form = q(doc, '[data-form="card"]');
        const status = statusFor(doc, 'card');

        // Delegated so it survives each re-render of the lookup result: the
        // "Show rulings" button fetches that card's rulings on demand.
        result.addEventListener('click', function (e) {
            const btn = e.target.closest && e.target.closest('[data-load-rulings]');
            if (!btn) return;
            const name = btn.getAttribute('data-card-name');
            const box = result.querySelector('[data-rulings-result]');
            if (!name || !box) return;
            btn.disabled = true;
            btn.textContent = 'Loading rulings…';
            getJson(rulingsUrl(name))
                .then(function (json) {
                    if (!json.ok) { btn.disabled = false; btn.textContent = json.error || 'No rulings found.'; return; }
                    renderRulings(box, json);
                    box.hidden = false;
                    btn.hidden = true;
                })
                .catch(function () { btn.disabled = false; btn.textContent = 'Show rulings'; });
        });

        // Same pattern for the "Show combos" button.
        result.addEventListener('click', function (e) {
            const btn = e.target.closest && e.target.closest('[data-load-combos]');
            if (!btn) return;
            const name = btn.getAttribute('data-card-name');
            const box = result.querySelector('[data-combos-result]');
            if (!name || !box) return;
            btn.disabled = true;
            btn.textContent = 'Loading combos…';
            getJson(combosUrl(name))
                .then(function (json) {
                    if (!json.ok) { btn.disabled = false; btn.textContent = json.error || 'No combos found.'; return; }
                    renderCombos(box, json);
                    box.hidden = false;
                    btn.hidden = true;
                })
                .catch(function () { btn.disabled = false; btn.textContent = 'Show combos'; });
        });

        if (form) {
            form.addEventListener('submit', function (e) {
                e.preventDefault();
                const name = (new FormData(form).get('name') || '').trim();
                if (!name) { setStatus(status, 'Enter a card name.'); return; }
                setStatus(status, 'Looking up…');
                hide(result);
                withBusy(form, true);
                getJson(cardUrl(name))
                    .then(function (json) {
                        if (!json.ok) { setStatus(status, json.error || 'No card found.'); return; }
                        setStatus(status, '');
                        renderCardLookup(result, json.card);
                        show(result);
                    })
                    .catch(function () { setStatus(status, 'Something went wrong. Try again.'); })
                    .then(function () { withBusy(form, false); });
            });
        }
    }

    function wireCompare(doc) {
        const form = q(doc, '[data-form="compare"]');
        if (!form) return;
        const status = statusFor(doc, 'compare');
        const result = q(doc, '[data-result="compare"]');
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            const data = new FormData(form);
            const listA = (data.get('listA') || '').trim();
            const listB = (data.get('listB') || '').trim();
            if (!listA && !listB) { setStatus(status, 'Paste a card list into both sides to compare.'); return; }
            setStatus(status, 'Comparing…');
            hide(result);
            withBusy(form, true);
            postJson('/magic/api/diff', { listA: listA, listB: listB })
                .then(function (json) {
                    if (!json.ok) { setStatus(status, json.error || 'Could not compare.'); return; }
                    setStatus(status, 'List A: ' + json.sizeA + ' cards · List B: ' + json.sizeB + ' cards');
                    renderDiff(result, json);
                    show(result);
                })
                .catch(function () { setStatus(status, 'Something went wrong. Try again.'); })
                .then(function () { withBusy(form, false); });
        });
    }

    function wireLegality(doc) {
        const form = q(doc, '[data-form="legality"]');
        if (!form) return;
        const status = statusFor(doc, 'legality');
        const result = q(doc, '[data-result="legality"]');
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            const data = new FormData(form);
            const list = (data.get('list') || '').trim();
            const format = data.get('format') || 'modern';
            if (!list) { setStatus(status, 'Paste a decklist to check.'); return; }
            setStatus(status, 'Resolving your deck and checking legality…');
            hide(result);
            withBusy(form, true);
            postJson(legalityUrl(), { list: list, format: format })
                .then(function (json) {
                    if (!json.ok) { setStatus(status, json.error || 'Could not check that deck.'); return; }
                    setStatus(status, (json.note ? json.note : '') +
                        (json.notFound && json.notFound.length ? ' Couldn’t find: ' + json.notFound.join(', ') : ''));
                    renderLegality(result, json);
                    show(result);
                })
                .catch(function () { setStatus(status, 'Something went wrong. Try again.'); })
                .then(function () { withBusy(form, false); });
        });
    }

    function wireReference(doc) {
        wireLookupForm(doc, 'set', 'code', setUrl, function (json) { return json.set; }, renderSet, 'Enter a set code.');
        wireLookupForm(doc, 'rule', 'term', ruleUrl, function (json) { return json.rule; }, renderRule, 'Enter a keyword.');
    }

    function wireWatch(doc) {
        const block = doc.querySelector('[data-watch-block]');
        if (!block) return; // logged out — nothing to wire
        const form = q(doc, '[data-form="watch"]');
        const status = statusFor(doc, 'watch');
        const result = q(doc, '[data-result="watch"]');

        function remove(id) {
            fetch(WATCHES_API + '?id=' + encodeURIComponent(id), { method: 'DELETE' })
                .then(function () { reload(); })
                .catch(function () { setStatus(status, 'Couldn’t remove that watch. Try again.'); });
        }
        function reload() {
            return getJson(WATCHES_API)
                .then(function (rows) { renderWatches(result, rows || [], remove); })
                .catch(function () { /* leave as-is on a transient error */ });
        }
        reload();

        if (form) form.addEventListener('submit', function (e) {
            e.preventDefault();
            const data = new FormData(form);
            const name = (data.get('name') || '').trim();
            const threshold = Number(data.get('threshold'));
            if (!name) { setStatus(status, 'Enter a card name.'); return; }
            if (!(threshold > 0)) { setStatus(status, 'Enter a positive target price.'); return; }
            setStatus(status, 'Adding…');
            withBusy(form, true);
            postJson(WATCHES_API, {
                name: name,
                direction: data.get('direction') || 'below',
                currency: data.get('currency') || 'usd',
                threshold: threshold,
            })
                .then(function (json) {
                    if (!json.ok) { setStatus(status, json.error || 'Couldn’t add that watch.'); return; }
                    setStatus(status, 'Watching ' + json.watch.cardName + '. You\'ll be DM\'d when it crosses your target.');
                    form.reset();
                    reload();
                })
                .catch(function () { setStatus(status, 'Something went wrong. Try again.'); })
                .then(function () { withBusy(form, false); });
        });
    }

    /** Shared wiring for a single-field GET lookup form (set / rule). */
    function wireLookupForm(doc, name, field, urlFn, pick, render, emptyMsg) {
        const form = q(doc, '[data-form="' + name + '"]');
        if (!form) return;
        const status = statusFor(doc, name);
        const result = q(doc, '[data-result="' + name + '"]');
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            const value = (new FormData(form).get(field) || '').trim();
            if (!value) { setStatus(status, emptyMsg); return; }
            setStatus(status, 'Looking up…');
            hide(result);
            withBusy(form, true);
            getJson(urlFn(value))
                .then(function (json) {
                    if (!json.ok) { setStatus(status, json.error || 'Not found.'); return; }
                    setStatus(status, '');
                    render(result, pick(json));
                    show(result);
                })
                .catch(function () { setStatus(status, 'Something went wrong. Try again.'); })
                .then(function () { withBusy(form, false); });
        });
    }

    function wireAsFan(doc) {
        const form = q(doc, '[data-form="asfan"]');
        if (!form) return;
        const status = statusFor(doc, 'asfan');
        const result = q(doc, '[data-result="asfan"]');
        const number = q(doc, '[data-asfan-number]');
        const sentence = q(doc, '[data-asfan-sentence]');
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            const data = new FormData(form);
            const params = { total: data.get('total'), cubeSize: data.get('cubeSize'), packSize: data.get('packSize') };
            setStatus(status, 'Calculating…');
            hide(result);
            withBusy(form, true);
            getJson(asfanUrl(params))
                .then(function (json) {
                    if (!json.ok) { setStatus(status, json.error || 'Invalid inputs.'); return; }
                    setStatus(status, '');
                    number.textContent = formatAsFan(json.value);
                    if (sentence) sentence.textContent = asfanSentence(json.value, Number(params.packSize));
                    show(result);
                })
                .catch(function () { setStatus(status, 'Something went wrong. Try again.'); })
                .then(function () { withBusy(form, false); });
        });
    }

    /** Validates the active source; returns an error string or null. */
    function sourceError(src) {
        if (src.mode === 'list') return src.list ? null : 'Paste a card list first (under "Your cube").';
        return src.query ? null : 'Enter a Scryfall search first (under "Your cube").';
    }

    function sourceLabel(src) {
        return src.mode === 'list' ? 'Your list' : src.query;
    }

    function wirePreview(doc) {
        const form = q(doc, '[data-form="preview"]');
        if (!form) return;
        const status = statusFor(doc, 'preview');
        const empty = emptyFor(doc, 'preview');
        const groups = q(doc, '[data-result="preview"]');
        const notFound = q(doc, '[data-notfound="preview"]');
        const dupes = q(doc, '[data-duplicates="preview"]');
        const breakdown = q(doc, '[data-breakdown="preview-analytics"]');
        const avgMv = q(doc, '[data-avg-mv="preview"]');
        const curve = q(doc, '[data-curve="preview"]');
        const types = q(doc, '[data-types="preview"]');
        const rarity = q(doc, '[data-rarity="preview"]');
        const pairs = q(doc, '[data-pairs="preview"]');
        const pips = q(doc, '[data-pips="preview"]');
        const value = q(doc, '[data-value="preview"]');
        const extremes = q(doc, '[data-value-extremes="preview"]');
        const valueCurrency = q(doc, '[data-value-currency="preview"]');
        const actions = q(doc, '[data-actions="preview"]');
        let lastTotalValues = [];
        let lastValueExtremes = [];

        // Switching currency re-renders the value lines from the cached payload —
        // no refetch, since every currency's totals/extremes are already present.
        if (valueCurrency) {
            valueCurrency.addEventListener('change', function () {
                renderTotalValue(value, lastTotalValues, valueCurrency.value);
                renderValueExtremes(extremes, lastValueExtremes, valueCurrency.value);
            });
        }
        const copyBtn = q(doc, '[data-copy="preview"]');
        const downloadBtn = q(doc, '[data-download="preview"]');
        const sampleBtn = q(doc, '[data-sample-pack="preview"]');
        const sampleDownloadBtn = q(doc, '[data-download-sample="preview"]');
        const sample = q(doc, '[data-sample="preview"]');
        let lastGroups = [];
        let lastSample = [];

        if (copyBtn) {
            const label = copyBtn.textContent;
            copyBtn.addEventListener('click', function () {
                if (!lastGroups.length) return;
                const text = cubeListText(lastGroups);
                if (root.navigator && root.navigator.clipboard) {
                    root.navigator.clipboard.writeText(text).then(
                        function () { copyBtn.textContent = '✓ Copied'; root.setTimeout(function () { copyBtn.textContent = label; }, 2000); },
                        function () { /* clipboard blocked — the download still works */ }
                    );
                }
            });
        }
        if (downloadBtn) {
            downloadBtn.addEventListener('click', function () {
                if (!lastGroups.length) return;
                downloadText('cube.txt', cubeListText(lastGroups));
            });
        }

        if (sampleBtn) {
            sampleBtn.addEventListener('click', function () {
                const src = activeSource(doc);
                const srcErr = sourceError(src);
                if (srcErr) { setStatus(status, srcErr); return; }
                const req = samplePackRequest(src, new FormData(form).get('packSize'));
                sampleBtn.disabled = true;
                if (sample) hide(sample);
                if (sampleDownloadBtn) hide(sampleDownloadBtn);
                const pending = req.method === 'POST' ? postJson(req.url, req.body) : getJson(req.url);
                pending
                    .then(function (json) {
                        if (!json.ok) { setStatus(status, json.error || 'Could not deal a pack.'); return; }
                        renderPacks(sample, json.packs);
                        show(sample);
                        // A sample is a deal like any other: let it be saved,
                        // and therefore loaded back on the Generate tab.
                        lastSample = json.packs;
                        if (sampleDownloadBtn) show(sampleDownloadBtn);
                    })
                    .catch(function () { setStatus(status, 'Something went wrong. Try again.'); })
                    .then(function () { sampleBtn.disabled = false; });
            });
        }

        if (sampleDownloadBtn) {
            sampleDownloadBtn.addEventListener('click', function () {
                if (!lastSample.length) return;
                downloadText('sample-pack.txt', packsToText(lastSample));
            });
        }

        form.addEventListener('submit', function (e) {
            e.preventDefault();
            const packSize = new FormData(form).get('packSize');
            const src = activeSource(doc);
            const err = sourceError(src);
            if (err) { setStatus(status, err); return; }
            setStatus(status, src.mode === 'list' ? 'Resolving your list…' : 'Fetching cards from Scryfall…');
            hide(groups); hide(notFound); hide(dupes); hide(breakdown); hide(actions); hide(sample);
            withBusy(form, true);
            setSpinner(doc, 'preview', true);
            const request = src.mode === 'list'
                ? postJson('/magic/api/preview', { list: src.list, packSize: Number(packSize) })
                : getJson(previewUrl({ query: src.query, packSize: packSize }));
            request
                .then(function (json) {
                    if (!json.ok) { setStatus(status, json.error || 'No results.'); return; }
                    setStatus(status, sourceLabel(src) + ' → ' + json.poolSize + ' cards' + (json.note ? ' · ' + json.note : ''));
                    hide(empty);
                    renderGroups(groups, json.groups);
                    show(groups);
                    lastGroups = json.groups || [];
                    show(actions);
                    renderNotFound(notFound, json.notFound);
                    lastTotalValues = (json.analytics && json.analytics.totalValues) || [];
                    lastValueExtremes = (json.analytics && json.analytics.valueExtremes) || [];
                    renderAnalytics(json.analytics, { dupes: dupes, breakdown: breakdown, avgMv: avgMv, curve: curve, types: types, rarity: rarity, pairs: pairs, pips: pips, value: value, extremes: extremes, valueCurrency: valueCurrency });
                })
                .catch(function () { setStatus(status, 'Something went wrong. Try again.'); })
                .then(function () { withBusy(form, false); setSpinner(doc, 'preview', false); });
        });
    }

    function wireGenerate(doc) {
        const form = q(doc, '[data-form="generate"]');
        if (!form) return;
        const status = statusFor(doc, 'generate');
        const empty = emptyFor(doc, 'generate');
        const summary = q(doc, '[data-summary="generate"]');
        const actions = q(doc, '[data-actions="generate"]');
        const breakdown = q(doc, '[data-breakdown="generate"]');
        const dist = q(doc, '[data-dist="generate"]');
        const result = q(doc, '[data-result="generate"]');
        const notFound = q(doc, '[data-notfound="generate"]');
        const downloadBtn = q(doc, '[data-download="generate"]');
        const importBtn = q(doc, '[data-import="generate"]');
        const importFile = q(doc, '[data-import-file="generate"]');
        const shareBtn = q(doc, '[data-share="generate"]');
        const shareResult = q(doc, '[data-share-deal-result]');
        const shareUrl = q(doc, '[data-share-deal-url]');
        const shareCopyBtn = q(doc, '[data-share-deal-copy]');
        let lastPacks = [];
        // What the current deal came from, for the file's provenance line:
        // the source it was dealt from, or the line a loaded file already had.
        let lastMeta = {};
        // The share token, once this deal has one — from sharing it, or from
        // having been opened by link. It's what makes per-seat links possible.
        let lastToken = '';

        /** Links each pack to its own seat page, once the deal has a token. */
        function packLinkFor(index) {
            if (!lastToken) return null;
            return dealPackUrl(root.location && root.location.origin, lastToken, index);
        }

        if (downloadBtn) {
            downloadBtn.addEventListener('click', function () {
                if (!lastPacks.length) return;
                downloadText('cube-packs.txt', packsToText(lastPacks, lastMeta));
            });
        }

        /**
         * Clears the result area before a deal is generated or loaded. The
         * share link goes too — it points at the old deal, not the new one.
         */
        function resetResults() {
            hide(summary); hide(actions); hide(breakdown); hide(result); hide(notFound);
            if (shareResult) hide(shareResult);
            // The old token belongs to the old deal — its seat links would
            // point at packs that are no longer on screen.
            lastToken = '';
            lastMeta = {};
        }

        /**
         * Paints a set of packs — shared by generating a new deal and
         * reloading a downloaded one, so both render identically (and both
         * leave the deal downloadable again).
         */
        function showPacks(json, summaryText) {
            hide(empty);
            lastPacks = json.packs;
            summary.textContent = summaryText;
            show(summary);
            renderPacks(result, json.packs, { packLink: packLinkFor });
            show(result);
            show(actions);
            renderDistribution(dist, json.distribution);
            show(breakdown);
            renderNotFound(notFound, json.notFound);
        }

        /**
         * Loads a pack list (from a file or a share link) and paints it.
         * Both routes go through /api/packs, so a link and a file can't
         * render differently.
         */
        function loadPacks(text, readingMessage, token) {
            setStatus(status, readingMessage);
            resetResults();
            setSpinner(doc, 'generate', true);
            const req = packImportRequest(text);
            return postJson(req.url, req.body)
                .then(function (json) {
                    if (!json.ok) { setStatus(status, json.error || 'Could not read that pack list.'); return; }
                    setStatus(status, '');
                    // Adopt the loaded deal's shape, so hitting Generate
                    // straight after re-deals to the same size rather than
                    // to whatever the boxes happened to say.
                    applyPackShape(form, json);
                    // Keep what the file said about itself, so re-downloading
                    // a loaded deal doesn't relabel it as dealt today.
                    lastMeta = { line: json.provenance || null, dealtOn: today() };
                    // Opened by link: the deal already has a token, so its
                    // packs can be handed out individually straight away.
                    lastToken = token || '';
                    showPacks(json, packsSummary(json));
                })
                .catch(function () { setStatus(status, 'Could not load those packs.'); })
                .then(function () { setSpinner(doc, 'generate', false); });
        }

        // Re-import: read a previously downloaded pack list and show those
        // exact packs. Deliberately independent of the cube source above —
        // the file already says which cards are in which pack.
        if (importBtn && importFile) {
            importBtn.addEventListener('click', function () { importFile.click(); });
            importFile.addEventListener('change', function () {
                const file = importFile.files && importFile.files[0];
                // Let the same file be picked again straight afterwards.
                importFile.value = '';
                const err = importFileError(file);
                if (err) { setStatus(status, err); return; }
                readFileText(file)
                    .then(function (text) { return loadPacks(text, 'Reading your pack list…'); })
                    .catch(function () { setStatus(status, 'Could not read that file.'); });
            });
        }

        // A deal opened from a share link: the page carries the pack list, so
        // it loads through exactly the same path as a picked file. A dead link
        // still switches tabs — its "not found" note lives in this panel.
        const sharedDeal = q(doc, '[data-shared-deal-packs]');
        const sharedDealToken = q(doc, '[data-shared-deal-token]');
        if (sharedDeal && sharedDeal.value.trim()) {
            activateTab(doc, 'generate');
            loadPacks(
                sharedDeal.value,
                'Loading the shared deal…',
                (sharedDealToken && sharedDealToken.value) || '',
            );
        } else if (q(doc, '[data-shared-deal-missing]')) {
            activateTab(doc, 'generate');
        }

        if (shareBtn) {
            shareBtn.addEventListener('click', function () {
                if (!lastPacks.length) return;
                const req = shareDealRequest(lastPacks, dealShareName(doc));
                setStatus(status, 'Creating link…');
                fetch(req.url, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(req.body),
                }).then(function (r) {
                    if (r.status === 401) { setStatus(status, 'Log in to create a share link.'); return null; }
                    if (!r.ok) { setStatus(status, 'Couldn’t create a link. Try again.'); return null; }
                    return r.json();
                }).then(function (json) {
                    if (!json) return;
                    const full = absoluteUrl(root.location && root.location.origin, json.url);
                    if (shareUrl) shareUrl.value = full;
                    if (shareResult) shareResult.hidden = false;
                    setStatus(status, 'Anyone with this link can open these packs. Each pack now has its own 🔗 for one seat.');
                    copyToClipboard(full);
                    // The deal has a token now, so every pack can be linked
                    // individually — repaint to hang those links off it.
                    lastToken = json.token;
                    renderPacks(result, lastPacks, { packLink: packLinkFor });
                }).catch(function () { setStatus(status, 'Couldn’t create a link. Try again.'); });
            });
        }

        if (shareCopyBtn) {
            shareCopyBtn.addEventListener('click', function () {
                if (shareUrl && shareUrl.value) copyToClipboard(shareUrl.value);
            });
        }

        form.addEventListener('submit', function (e) {
            e.preventDefault();
            const data = new FormData(form);
            const packs = data.get('packs');
            const packSize = data.get('packSize');
            const balanced = form.querySelector('[name="balanced"]').checked;
            const src = activeSource(doc);
            const err = sourceError(src);
            if (err) { setStatus(status, err); return; }
            setStatus(status, src.mode === 'list' ? 'Resolving your list and dealing packs…' : 'Drawing cards and dealing packs…');
            resetResults();
            withBusy(form, true);
            setSpinner(doc, 'generate', true);
            const request = src.mode === 'list'
                ? postJson('/magic/api/generate', { list: src.list, packs: Number(packs), packSize: Number(packSize), balanced: balanced })
                : getJson(generateUrl({ query: src.query, packs: packs, packSize: packSize, balanced: balanced }));
            request
                .then(function (json) {
                    if (!json.ok) { setStatus(status, json.error || 'Could not build packs.'); return; }
                    setStatus(status, '');
                    lastMeta = { source: sourceLabel(src), dealtOn: today() };
                    showPacks(
                        json,
                        'Dealt ' + json.packCount + ' packs of ' + json.packSize +
                        ' from a ' + json.poolSize + '-card pool. Click any card to view it on Scryfall.' +
                        (json.note ? ' ' + json.note : '')
                    );
                })
                .catch(function () { setStatus(status, 'Something went wrong building packs.'); })
                .then(function () { withBusy(form, false); setSpinner(doc, 'generate', false); });
        });
    }

    /**
     * Hover-to-enlarge: one shared floating image, shown while the pointer
     * is over any card tile that carries a `data-large` URL, positioned
     * next to the cursor. Event-delegated on the document so it covers
     * tiles rendered after load. Hard-off on touch via CSS (`hover: none`).
     */
    function wireZoom(doc) {
        if (!doc.body) return;
        const overlay = doc.createElement('figure');
        overlay.className = 'cube-zoom';
        overlay.hidden = true;
        overlay.setAttribute('aria-hidden', 'true');
        const overlayImg = doc.createElement('img');
        overlayImg.className = 'cube-zoom-img';
        overlayImg.alt = '';
        const overlayStat = doc.createElement('figcaption');
        overlayStat.className = 'cube-zoom-stat';
        overlay.appendChild(overlayImg);
        overlay.appendChild(overlayStat);
        doc.body.appendChild(overlay);

        const view = doc.defaultView || { innerWidth: 1024, innerHeight: 768 };

        function place(e) {
            const w = overlay.offsetWidth || 240;
            const h = overlay.offsetHeight || 360;
            const pos = zoomPosition(e.clientX, e.clientY, w, h, view.innerWidth, view.innerHeight);
            overlay.style.left = pos.left + 'px';
            overlay.style.top = pos.top + 'px';
        }

        function cardFrom(node) {
            return node && node.closest ? node.closest('.cube-card[data-large]') : null;
        }

        doc.addEventListener('mouseover', function (e) {
            const card = cardFrom(e.target);
            if (!card) return;
            overlayImg.src = card.getAttribute('data-large');
            const stat = card.getAttribute('data-statline') || '';
            overlayStat.textContent = stat;
            overlayStat.hidden = stat === '';
            overlay.hidden = false;
            place(e);
        });
        doc.addEventListener('mousemove', function (e) {
            if (!overlay.hidden) place(e);
        });
        doc.addEventListener('mouseout', function (e) {
            const card = cardFrom(e.target);
            if (!card) return;
            // Ignore moves between the card's own children.
            if (cardFrom(e.relatedTarget) === card) return;
            overlay.hidden = true;
            overlayImg.removeAttribute('src');
        });
    }

    /**
     * Tap-to-enlarge for touch / no-hover devices (the mobile counterpart
     * to wireZoom). Tapping a card opens a centered lightbox with the
     * full-size image, stat line, and a "View on Scryfall" link, instead
     * of navigating straight off the page. Dismissed by the close button,
     * a backdrop tap, or Escape. On hover devices taps fall through to the
     * normal link.
     */
    function wireLightbox(doc) {
        if (!doc.body) return;
        const modal = doc.createElement('div');
        modal.className = 'cube-lightbox';
        modal.hidden = true;
        modal.setAttribute('role', 'dialog');
        modal.setAttribute('aria-modal', 'true');
        modal.setAttribute('aria-label', 'Card preview');

        const closeBtn = doc.createElement('button');
        closeBtn.type = 'button';
        closeBtn.className = 'cube-lightbox-close';
        closeBtn.setAttribute('aria-label', 'Close');
        closeBtn.textContent = '✕';

        const figure = doc.createElement('figure');
        figure.className = 'cube-lightbox-card';
        const img = doc.createElement('img');
        img.className = 'cube-lightbox-img';
        img.alt = '';
        const stat = doc.createElement('figcaption');
        stat.className = 'cube-lightbox-stat';
        const link = doc.createElement('a');
        link.className = 'btn btn-secondary cube-lightbox-link';
        link.target = '_blank';
        link.rel = 'noopener';
        link.textContent = 'View on Scryfall ↗';
        figure.appendChild(img);
        figure.appendChild(stat);
        figure.appendChild(link);
        modal.appendChild(closeBtn);
        modal.appendChild(figure);
        doc.body.appendChild(modal);

        function open(card) {
            img.src = card.getAttribute('data-large');
            const line = card.getAttribute('data-statline') || '';
            stat.textContent = line;
            stat.hidden = line === '';
            link.href = card.getAttribute('href') || '#';
            modal.hidden = false;
        }
        function close() {
            modal.hidden = true;
            img.removeAttribute('src');
        }

        doc.addEventListener('click', function (e) {
            // A flip-button tap is handled by wireCardFlip, not the lightbox.
            if (e.target.closest && e.target.closest('.cube-card-flip')) return;
            const card = e.target.closest && e.target.closest('.cube-card[data-large]');
            if (!card || !prefersTap()) return;
            // Search-result tiles open the full detail panel (price, rulings,
            // combos) instead — same as a desktop click — so the bare-image
            // lightbox is reserved for the preview/generate grids, which have
            // no panel to open.
            if (card.closest && card.closest('[data-result="search"]')) return;
            e.preventDefault();
            open(card);
        });
        closeBtn.addEventListener('click', close);
        modal.addEventListener('click', function (e) {
            if (e.target === modal) close(); // backdrop, not the figure
        });
        doc.addEventListener('keydown', function (e) {
            if (e.key === 'Escape' && !modal.hidden) close();
        });
    }

    /**
     * Flips a double-faced card's tile in place: swaps the thumbnail image and
     * the `data-large` URL (which drives the hover-zoom and lightbox) between
     * the front and back faces. Event-delegated so it covers tiles rendered
     * after load. preventDefault stops the tile's Scryfall link from firing.
     */
    function wireCardFlip(doc) {
        doc.addEventListener('click', function (e) {
            const flip = e.target.closest && e.target.closest('.cube-card-flip');
            if (!flip) return;
            e.preventDefault();
            e.stopPropagation();
            const card = flip.closest('.cube-card');
            if (!card || !card.getAttribute('data-back')) return;
            const img = card.querySelector('img.cube-card-img');
            const flipped = card.getAttribute('data-flipped') === 'true';
            if (flipped) {
                card.setAttribute('data-large', card.getAttribute('data-front-large') || '');
                if (img) img.src = card.getAttribute('data-front-thumb') || img.src;
                card.setAttribute('data-flipped', 'false');
            } else {
                const back = card.getAttribute('data-back');
                card.setAttribute('data-large', back);
                if (img) img.src = back;
                card.setAttribute('data-flipped', 'true');
            }
        });
    }

    function wire(doc) {
        wireTabs(doc);
        wireHashChange(doc);
        wireSource(doc);
        wireSavedLists(doc);
        wireShare(doc);
        wireCardAutocomplete(doc);
        prefillFromUrl(doc);
        // A server-rendered shared cube wins over a ?q= / ?list= prefill.
        revealSharedCube(doc);
        wireCardCount(doc);
        wireCopyQuery(doc);
        wireExamples(doc);
        wireAsFan(doc);
        wireCompare(doc);
        wireSearch(doc);
        wireCardLookup(doc);
        wireLegality(doc);
        wireReference(doc);
        wireWatch(doc);
        wirePreview(doc);
        wireGenerate(doc);
        wireCardFlip(doc);
        wireZoom(doc);
        wireLightbox(doc);
    }

    if (root && root.document) wire(root.document);


    const api = {
        formatAsFan: formatAsFan,
        asfanSentence: asfanSentence,
        categoryColor: categoryColor,
        scryfallCardUrl: scryfallCardUrl,
        cardStatline: cardStatline,
        manaSymbolUrls: manaSymbolUrls,
        tabIdFromHash: tabIdFromHash,
        zoomPosition: zoomPosition,
        deleteListUrl: deleteListUrl,
        absoluteUrl: absoluteUrl,
        queryShareUrl: queryShareUrl,
        readUrlPrefill: readUrlPrefill,
        prefillFromUrl: prefillFromUrl,
        revealSharedCube: revealSharedCube,
        countCards: countCards,
        scryfallAutocompleteUrl: scryfallAutocompleteUrl,
        currentLineInfo: currentLineInfo,
        splitQuantityPrefix: splitQuantityPrefix,
        applyCardChoice: applyCardChoice,
        packsToText: packsToText,
        packHeader: packHeader,
        provenanceLine: provenanceLine,
        dealPackUrl: dealPackUrl,
        cardCountLabel: cardCountLabel,
        notFoundText: notFoundText,
        applyPackShape: applyPackShape,
        importFileError: importFileError,
        packImportRequest: packImportRequest,
        shareDealRequest: shareDealRequest,
        dealShareName: dealShareName,
        packsSummary: packsSummary,
        groupsToList: groupsToList,
        cubeListText: cubeListText,
        asfanUrl: asfanUrl,
        previewUrl: previewUrl,
        generateUrl: generateUrl,
        samplePackRequest: samplePackRequest,
        renderDistribution: renderDistribution,
        renderDiff: renderDiff,
        legalityUrl: legalityUrl,
        renderLegality: renderLegality,
        setUrl: setUrl,
        ruleUrl: ruleUrl,
        renderSet: renderSet,
        renderRule: renderRule,
        watchLine: watchLine,
        renderWatches: renderWatches,
        cardUrl: cardUrl,
        searchUrl: searchUrl,
        searchSentence: searchSentence,
        renderSearchResults: renderSearchResults,
        rulingsUrl: rulingsUrl,
        combosUrl: combosUrl,
        renderCombos: renderCombos,
        priceLine: priceLine,
        totalValueText: totalValueText,
        formatMoney: formatMoney,
        renderCardLookup: renderCardLookup,
        cardDetailModal: cardDetailModal,
        renderRulings: renderRulings,
        renderTotalValue: renderTotalValue,
        renderValueExtremes: renderValueExtremes,
        renderManaCurve: renderManaCurve,
        renderTypeBreakdown: renderTypeBreakdown,
        renderRarity: renderRarity,
        renderColorPairs: renderColorPairs,
        renderColorPips: renderColorPips,
        renderDuplicates: renderDuplicates,
        renderAnalytics: renderAnalytics,
        renderGroups: renderGroups,
        renderPacks: renderPacks,
    };
    if (root) root.TobyCube = api;
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = api;
    }
})(typeof window !== 'undefined' ? window : null);
