/**
 * Shared motion primitives — count-up + reveal-on-scroll.
 *
 * The module auto-runs on DOMContentLoaded in the browser, but we
 * require() it once per test to get a fresh DOM each time. The
 * `init`-on-load path is feature-detected so jest's jsdom (with no
 * IntersectionObserver, optional rAF) is exercised here too.
 */

describe('motion.js', () => {
    let motion;

    beforeEach(() => {
        jest.resetModules();
        // Clear the DOM from any previous test — the module auto-init runs
        // on require() and would otherwise observe leftover [data-reveal]
        // nodes against the freshly-mocked IntersectionObserver.
        document.body.innerHTML = '';
        delete window.IntersectionObserver;
        // Default: no reduced-motion preference, IO present, rAF present.
        window.matchMedia = jest.fn().mockImplementation((q) => ({
            matches: false,
            media: q,
            addEventListener: () => {},
            removeEventListener: () => {},
            addListener: () => {},
            removeListener: () => {},
        }));
        // Provide a synchronous rAF so count-up finishes inside one tick.
        global.requestAnimationFrame = (cb) => cb(performance.now() + 2000);
        motion = require('../../main/resources/static/js/motion');
    });

    afterEach(() => {
        delete global.requestAnimationFrame;
        delete window.IntersectionObserver;
    });

    // -----------------------------------------------------------------------
    // countUp
    // -----------------------------------------------------------------------

    describe('countUp', () => {
        test('resolves to the target value once rAF advances past the duration', () => {
            const el = document.createElement('span');
            el.setAttribute('data-count-up', '');
            el.textContent = '1234';
            motion.countUp(el);
            expect(el.textContent).toBe('1234');
        });

        test('preserves thousands-separator formatting on the final value', () => {
            const el = document.createElement('span');
            el.textContent = '1,234,567';
            motion.countUp(el);
            expect(el.textContent).toBe('1,234,567');
        });

        test('skips animation when prefers-reduced-motion matches', () => {
            window.matchMedia = jest.fn().mockReturnValue({
                matches: true,
                addEventListener: () => {},
                addListener: () => {},
            });
            jest.resetModules();
            const m = require('../../main/resources/static/js/motion');
            // Make rAF reject so we'd notice if it was called.
            global.requestAnimationFrame = jest.fn();
            const el = document.createElement('span');
            el.textContent = '500';
            m.countUp(el);
            expect(el.textContent).toBe('500');
            expect(global.requestAnimationFrame).not.toHaveBeenCalled();
        });

        test('is a no-op for non-numeric text', () => {
            const el = document.createElement('span');
            el.textContent = 'Coming soon';
            motion.countUp(el);
            expect(el.textContent).toBe('Coming soon');
        });

        test('marks the element done so re-running is a no-op', () => {
            const el = document.createElement('span');
            el.textContent = '42';
            motion.countUp(el);
            expect(el.dataset.countUpDone).toBe('1');
            // Mutate the text to something different and re-run — should not retouch.
            el.textContent = 'unchanged';
            motion.countUp(el);
            expect(el.textContent).toBe('unchanged');
        });

        test('handles zero target without spinning rAF', () => {
            global.requestAnimationFrame = jest.fn();
            const el = document.createElement('span');
            el.textContent = '0';
            motion.countUp(el);
            expect(el.textContent).toBe('0');
            expect(global.requestAnimationFrame).not.toHaveBeenCalled();
        });
    });

    // -----------------------------------------------------------------------
    // revealOnScroll
    // -----------------------------------------------------------------------

    describe('revealOnScroll', () => {
        test('falls back to immediate reveal when IntersectionObserver is unavailable', () => {
            // jsdom has no IO by default — we rely on that exact code path.
            delete window.IntersectionObserver;
            document.body.innerHTML = `
                <section data-reveal>one</section>
                <section data-reveal>two</section>
            `;
            motion.revealOnScroll();
            const sections = document.querySelectorAll('[data-reveal]');
            sections.forEach((s) => expect(s.classList.contains('is-revealed')).toBe(true));
        });

        test('reveals all elements immediately under prefers-reduced-motion', () => {
            window.matchMedia = jest.fn().mockReturnValue({
                matches: true,
                addEventListener: () => {},
                addListener: () => {},
            });
            // Even with IO present, reduced-motion should short-circuit.
            window.IntersectionObserver = jest.fn(function () {
                this.observe = jest.fn();
                this.unobserve = jest.fn();
            });
            jest.resetModules();
            const m = require('../../main/resources/static/js/motion');
            document.body.innerHTML = `<section data-reveal>x</section>`;
            m.revealOnScroll();
            const sect = document.querySelector('[data-reveal]');
            expect(sect.classList.contains('is-revealed')).toBe(true);
            expect(window.IntersectionObserver).not.toHaveBeenCalled();
        });

        test('uses IntersectionObserver and unobserves once revealed', () => {
            let savedCallback = null;
            const observe = jest.fn();
            const unobserve = jest.fn();
            class MockIO {
                constructor(cb) {
                    savedCallback = cb;
                    this.observe = observe;
                    this.unobserve = unobserve;
                }
            }
            window.IntersectionObserver = MockIO;
            jest.resetModules();
            const m = require('../../main/resources/static/js/motion');
            document.body.innerHTML = `<section data-reveal>x</section>`;
            m.revealOnScroll();
            expect(observe).toHaveBeenCalledTimes(1);
            const el = document.querySelector('[data-reveal]');
            savedCallback([{ isIntersecting: true, target: el }]);
            expect(el.classList.contains('is-revealed')).toBe(true);
            expect(unobserve).toHaveBeenCalledWith(el);
        });

        test('skips non-intersecting entries', () => {
            let cb = null;
            class MockIO {
                constructor(c) {
                    cb = c;
                    this.observe = jest.fn();
                    this.unobserve = jest.fn();
                }
            }
            window.IntersectionObserver = MockIO;
            jest.resetModules();
            const m = require('../../main/resources/static/js/motion');
            document.body.innerHTML = `<section data-reveal>x</section>`;
            m.revealOnScroll();
            const el = document.querySelector('[data-reveal]');
            cb([{ isIntersecting: false, target: el }]);
            expect(el.classList.contains('is-revealed')).toBe(false);
        });

        test('is a no-op when no [data-reveal] elements exist', () => {
            const ioCtor = jest.fn();
            window.IntersectionObserver = ioCtor;
            jest.resetModules();
            const m = require('../../main/resources/static/js/motion');
            document.body.innerHTML = `<p>nothing here</p>`;
            m.revealOnScroll();
            expect(ioCtor).not.toHaveBeenCalled();
        });
    });

    // -----------------------------------------------------------------------
    // runAllCountUps
    // -----------------------------------------------------------------------

    describe('runAllCountUps', () => {
        test('triggers count-up on every [data-count-up] descendant', () => {
            document.body.innerHTML = `
                <span data-count-up>10</span>
                <span data-count-up>20</span>
                <span>not animated</span>
            `;
            motion.runAllCountUps();
            const spans = document.querySelectorAll('[data-count-up]');
            spans.forEach((s) => expect(s.dataset.countUpDone).toBe('1'));
        });
    });
});
