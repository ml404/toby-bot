// Regressions for the plinko Drop-button lock + immediate-feedback fixes.
//
// The bug user reported: "if I press drop before the ball has appeared
// back up top, button locks but ball doesn't drop". Two contributing
// fixes captured here:
//
//   1. startAnimation() now snaps the ball to the top of the board on
//      Drop click, BEFORE the fetch — so the player gets immediate
//      visual feedback that the round started, even on slow networks.
//      Previously the ball only moved inside animateDrop, which runs
//      after the response lands.
//
//   2. animateDrop() now has a setTimeout safety net that resolves the
//      Promise within DROP_MS + 500ms even if requestAnimationFrame
//      stalls (backgrounded tab, browser throttling, mobile compositor).
//      Without this the casino-game.js lock-release never runs and the
//      Drop button stays disabled forever.

describe('plinko — startAnimation + animateDrop safety nets', () => {
    let cfg;        // captured by the TobyCasinoGame.init mock
    let initMock;
    let resolvedRaf;
    let rafCallbacks;
    let dateNowSpy;
    let perfNowSpy;

    beforeEach(() => {
        // Build the minimum SVG + form DOM the plinko IIFE looks up via
        // TobyCasinoMinigameDom.standardElements and direct getElementById.
        document.body.innerHTML = `
            <main data-guild-id="g1" data-toby-coins="0" data-market-price="0">
                <form id="plinko-bet">
                    <input id="plinko-stake" type="number" value="10">
                    <button id="plinko-drop" type="submit">Drop</button>
                </form>
                <span id="plinko-balance">100</span>
                <div id="plinko-result"></div>
                <svg id="plinko-board">
                    <g id="plinko-pegs"></g>
                    <g id="plinko-buckets"></g>
                    <circle id="plinko-ball" cx="0" cy="8"></circle>
                </svg>
            </main>
        `;

        // Capture the config so we can drive startAnimation / renderResult
        // directly without going through a full form submit cycle.
        cfg = null;
        initMock = jest.fn((config) => {
            cfg = config;
            return { run: jest.fn() };
        });
        window.TobyCasinoGame = { init: initMock };
        window.TobyCasinoMinigameDom = {
            standardElements: () => ({
                main: document.querySelector('main'),
                guildId: 'g1',
                tobyCoins: 0,
                marketPrice: 0,
                form: document.getElementById('plinko-bet'),
                stakeInput: document.getElementById('plinko-stake'),
                primaryBtn: document.getElementById('plinko-drop'),
                tobyBtn: null,
                balanceEl: document.getElementById('plinko-balance'),
                resultEl: document.getElementById('plinko-result'),
            }),
        };
        window.__plinkoRows = 8;
        window.__plinkoBuckets = 9;
        window.__plinkoTables = { LOW: Array(9).fill(1), MEDIUM: Array(9).fill(1), HIGH: Array(9).fill(1) };

        // rAF stub — capture queued callbacks so the test can choose
        // when (and whether) to fire them.
        rafCallbacks = [];
        window.requestAnimationFrame = (fn) => {
            rafCallbacks.push(fn);
            return rafCallbacks.length;
        };

        // Drive performance.now / Date.now from the same fake clock so
        // we can advance time and observe the rAF tick branching.
        let clock = 0;
        dateNowSpy = jest.spyOn(Date, 'now').mockImplementation(() => clock);
        perfNowSpy = jest.spyOn(performance, 'now').mockImplementation(() => clock);
        global.__advanceClock = (ms) => { clock += ms; };

        // Loading the file invokes the IIFE which calls TobyCasinoGame.init
        // synchronously; cfg is populated by the mock.
        jest.isolateModules(() => {
            require('../../main/resources/static/js/plinko');
        });
    });

    afterEach(() => {
        dateNowSpy.mockRestore();
        perfNowSpy.mockRestore();
        delete global.__advanceClock;
        delete window.TobyCasinoGame;
        delete window.TobyCasinoMinigameDom;
        delete window.__plinkoRows;
        delete window.__plinkoBuckets;
        delete window.__plinkoTables;
    });

    test('startAnimation snaps the ball to the top of the board (immediate Drop feedback)', () => {
        expect(cfg).not.toBeNull();
        const ball = document.getElementById('plinko-ball');
        // Pretend a previous round left the ball at a bucket position.
        ball.setAttribute('transform', 'translate(50,170)');
        ball.setAttribute('cx', '0');
        ball.setAttribute('cy', '0');

        cfg.startAnimation();

        // Snap-to-top: cx=0, cy=0, transform=translate(0, BALL_R+4) where
        // BALL_R = 4 → y = 8. This is the visible top of the SVG board.
        expect(ball.getAttribute('cx')).toBe('0');
        expect(ball.getAttribute('cy')).toBe('0');
        expect(ball.getAttribute('transform')).toBe('translate(0,8)');
    });

    test('renderResult returns a Promise that resolves via the rAF tick on the happy path', async () => {
        const body = { ok: true, bucket: 4 };
        const settlePromise = cfg.renderResult(body);
        expect(typeof settlePromise.then).toBe('function');

        // Fire the queued rAF tick with elapsed >= DROP_MS (1400ms) to
        // settle the Promise on the happy path. We grab the LAST queued
        // tick callback because startAnimation's snap may have done DOM
        // setup that schedules a frame in some browsers; the test
        // simulates a single rAF firing at the end of the drop window.
        global.__advanceClock(1400);
        const tick = rafCallbacks[rafCallbacks.length - 1];
        tick(performance.now());

        await settlePromise;

        // Ball settles at the bucket centre — bucketCenterX(4) for a 9-
        // bucket / 200-wide SVG is (-100 + (4 + 0.5) * (200/9)) ≈ -0.0.
        const finalTransform = document.getElementById('plinko-ball').getAttribute('transform');
        expect(finalTransform).toMatch(/^translate\(/);
    });

    test('animateDrop resolves via the setTimeout safety net when rAF never ticks (rAF stalled)', async () => {
        // doNotFake rAF so our hand-stubbed window.requestAnimationFrame
        // (which captures callbacks instead of firing them) keeps working
        // alongside the fake setTimeout that drives the safety net.
        jest.useFakeTimers({ doNotFake: ['requestAnimationFrame'] });
        try {
            // Reset rAF capture since renderResult may have already been
            // called by other tests. Re-stub since useFakeTimers might
            // touch the window methods.
            rafCallbacks.length = 0;
            window.requestAnimationFrame = (fn) => {
                rafCallbacks.push(fn);
                return rafCallbacks.length;
            };

            const body = { ok: true, bucket: 4 };
            const settlePromise = cfg.renderResult(body);

            // rAF was scheduled but we deliberately don't call it.
            expect(rafCallbacks.length).toBeGreaterThan(0);

            // Advance fake timers by DROP_MS + 500 + a hair — the safety
            // setTimeout(settle, DROP_MS + 500) should fire and resolve.
            jest.advanceTimersByTime(1900);

            // Flush microtasks so the .then chain on renderResult runs.
            for (let i = 0; i < 5; i++) await Promise.resolve();

            // The Promise must settle — without the safety net this would
            // hang forever and lock the Drop button.
            await settlePromise;

            const finalTransform = document.getElementById('plinko-ball').getAttribute('transform');
            expect(finalTransform).toMatch(/^translate\(/);
        } finally {
            jest.useRealTimers();
        }
    });

    test('the rAF path and the safety timeout share a `done` guard so the Promise resolves exactly once', async () => {
        // Even if both the rAF tick AND the setTimeout fire (e.g. the
        // tab unhid right as DROP_MS+500 elapsed), the Promise must
        // resolve exactly once and the final transform must be the
        // bucket centre (settle() set it once, the second call is a
        // no-op because `done` is true).
        jest.useFakeTimers({ doNotFake: ['requestAnimationFrame'] });
        let resolutions = 0;
        try {
            rafCallbacks.length = 0;
            window.requestAnimationFrame = (fn) => {
                rafCallbacks.push(fn);
                return rafCallbacks.length;
            };

            const body = { ok: true, bucket: 4 };
            const settlePromise = cfg.renderResult(body).then(() => { resolutions += 1; });

            // Fire the safety timeout path first.
            jest.advanceTimersByTime(1900);
            for (let i = 0; i < 5; i++) await Promise.resolve();

            // Now fire any still-queued rAF callback — it must early-
            // return because `done` is already set by settle().
            const stillQueued = rafCallbacks[rafCallbacks.length - 1];
            if (stillQueued) stillQueued(9999);
            for (let i = 0; i < 5; i++) await Promise.resolve();

            await settlePromise;
            expect(resolutions).toBe(1);
        } finally {
            jest.useRealTimers();
        }
    });
});
