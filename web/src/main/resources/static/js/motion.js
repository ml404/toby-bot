// Shared motion primitives: data-count-up + data-reveal.
//
// Two opt-in behaviours so any page can drop a `data-count-up` on a
// number or `data-reveal` on a section without wiring its own animation
// frame loop. Both no-op under prefers-reduced-motion so we never burn
// CPU for users who've asked us not to.
//
// data-count-up:
//   <span data-count-up>1234</span>
//   The element's text is parsed as the target integer; the animation
//   counts from 0 → target over ~1.2s with an ease-out curve. The DOM
//   text is updated each frame; commas in the original (e.g. "1,234")
//   are preserved on the final value, intermediate frames are plain
//   integers — readable but doesn't churn the layout each tick because
//   tabular-nums is on the consuming class.
//
// data-reveal:
//   <section data-reveal>...</section>
//   IntersectionObserver adds .is-revealed once the element is 15%
//   visible. CSS in base.css owns the opacity/transform — JS only
//   flips the class. Each element is unobserved after firing so we
//   don't pay for sentries after the section has been seen.

(function (root) {
    'use strict';

    function prefersReducedMotion() {
        if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false;
        try {
            return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
        } catch (_) {
            return false;
        }
    }

    function parseTarget(text) {
        if (text == null) return null;
        const cleaned = String(text).replace(/[, ]/g, '').trim();
        if (!cleaned) return null;
        const n = Number(cleaned);
        return Number.isFinite(n) ? n : null;
    }

    // Locale-aware thousands separator for the final value (matches what
    // the server-rendered template emits) — we keep the original text as
    // the source of truth so 1,234 stays 1,234 once the count-up settles.
    function formatFinal(originalText, target) {
        const hadComma = /,/.test(String(originalText || ''));
        if (!hadComma) return String(target);
        try {
            return target.toLocaleString('en-US');
        } catch (_) {
            return String(target);
        }
    }

    // ease-out cubic — fast start, gentle finish. Same shape as the
    // --ease-out token in base.css so JS-driven motion matches CSS.
    function easeOut(t) {
        const u = 1 - t;
        return 1 - u * u * u;
    }

    function countUp(el, opts) {
        if (!el || typeof el !== 'object') return;
        if (el.dataset && el.dataset.countUpDone === '1') return;
        const original = el.textContent || '';
        const target = parseTarget(original);
        if (target == null) return;
        // Mark immediately so re-entry (e.g. window focus) doesn't double-run.
        if (el.dataset) el.dataset.countUpDone = '1';

        if (prefersReducedMotion() || target === 0) {
            el.textContent = formatFinal(original, target);
            return;
        }

        const duration = (opts && typeof opts.duration === 'number') ? opts.duration : 1200;
        const start = (typeof performance !== 'undefined' && performance.now)
            ? performance.now()
            : Date.now();

        function step(now) {
            const elapsed = now - start;
            const t = Math.min(1, elapsed / duration);
            const value = Math.round(target * easeOut(t));
            el.textContent = t < 1 ? String(value) : formatFinal(original, target);
            if (t < 1 && typeof requestAnimationFrame === 'function') {
                requestAnimationFrame(step);
            }
        }
        if (typeof requestAnimationFrame === 'function') {
            requestAnimationFrame(step);
        } else {
            el.textContent = formatFinal(original, target);
        }
    }

    function runAllCountUps(rootEl) {
        const scope = rootEl || (typeof document !== 'undefined' ? document : null);
        if (!scope) return;
        const els = scope.querySelectorAll('[data-count-up]');
        els.forEach(function (el) { countUp(el); });
    }

    function revealOnScroll(rootEl) {
        const scope = rootEl || (typeof document !== 'undefined' ? document : null);
        if (!scope) return;
        const els = scope.querySelectorAll('[data-reveal]');
        if (!els.length) return;

        // No IntersectionObserver (jsdom, very old browser) → reveal everything
        // immediately so users don't see a permanently invisible section.
        if (typeof IntersectionObserver !== 'function') {
            els.forEach(function (el) { el.classList.add('is-revealed'); });
            return;
        }
        // Reduced motion → same: skip the stagger, just resolve to the final state.
        if (prefersReducedMotion()) {
            els.forEach(function (el) { el.classList.add('is-revealed'); });
            return;
        }
        const io = new IntersectionObserver(function (entries) {
            entries.forEach(function (entry) {
                if (!entry.isIntersecting) return;
                entry.target.classList.add('is-revealed');
                io.unobserve(entry.target);
            });
        }, { threshold: 0.15, rootMargin: '0px 0px -40px 0px' });
        els.forEach(function (el) { io.observe(el); });
    }

    function init() {
        runAllCountUps();
        revealOnScroll();
    }

    if (typeof document !== 'undefined' && document.addEventListener) {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', init);
        } else {
            init();
        }
    }

    const api = { countUp, runAllCountUps, revealOnScroll, prefersReducedMotion };
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = api;
    } else if (root) {
        root.TobyMotion = api;
    }
})(typeof window !== 'undefined' ? window : null);
