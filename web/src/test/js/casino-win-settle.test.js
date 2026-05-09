// Pure-DOM tests for the shared casino-win-settle helper. Owns the
// "play win-or-lose cue + drop a chip flourish on the felt" pair that
// every minigame used to do by hand. These tests are the one place
// chip-stack assertions still live (per-game render tests have been
// trimmed to assert only their game-specific bits).
//
// Order matters: load the dependencies before the helper so its
// `window.CasinoSounds` / `window.CasinoRender` references resolve.

require('../../main/resources/static/js/casino-render');
require('../../main/resources/static/js/casino-win-settle');

describe('TobyCasinoWinSettle.fire', () => {
    let target;
    let playSpy;

    beforeEach(() => {
        document.body.innerHTML = '<section id="t"></section>';
        target = document.getElementById('t');
        // Stub the sounds module — the helper just calls .play(name).
        playSpy = jest.fn();
        window.CasinoSounds = { play: playSpy };
    });

    afterEach(() => {
        delete window.CasinoSounds;
    });

    test('plays "win" + drops a chip stack on a winning body', () => {
        window.TobyCasinoWinSettle.fire({ win: true, net: 250 }, target);

        expect(playSpy).toHaveBeenCalledWith('win');
        const stack = target.querySelector('.casino-chip-stack');
        expect(stack).not.toBeNull();
        expect(stack.querySelector('.casino-chip-payout').textContent).toBe('+250');
    });

    test('plays "lose" + leaves the felt clean on a losing body', () => {
        window.TobyCasinoWinSettle.fire({ win: false, net: -100 }, target);

        expect(playSpy).toHaveBeenCalledWith('lose');
        expect(target.querySelector('.casino-chip-stack')).toBeNull();
    });

    test('synthesises win from net > 0 when body has no `win` field (highlow / scratch)', () => {
        window.TobyCasinoWinSettle.fire({ net: 200, jackpotPayout: 0 }, target);

        expect(playSpy).toHaveBeenCalledWith('win');
        const stack = target.querySelector('.casino-chip-stack');
        expect(stack).not.toBeNull();
        expect(stack.querySelector('.casino-chip-payout').textContent).toBe('+200');
    });

    test('plays "lose" when net <= 0 and `win` field absent', () => {
        window.TobyCasinoWinSettle.fire({ net: -50 }, target);

        expect(playSpy).toHaveBeenCalledWith('lose');
        expect(target.querySelector('.casino-chip-stack')).toBeNull();
    });

    test('push (baccarat tie) suppresses both sound and flash', () => {
        window.TobyCasinoWinSettle.fire({ push: true, net: 0 }, target);

        expect(playSpy).not.toHaveBeenCalled();
        expect(target.querySelector('.casino-chip-stack')).toBeNull();
    });

    test('jackpot payout drives the chip-stack amount over net', () => {
        // Jackpot wins — bigger stack, label reads the jackpot payout
        // (helper picks jackpotPayout > net via flashWinPayout).
        window.TobyCasinoWinSettle.fire(
            { win: true, net: 500, jackpotPayout: 4000 }, target
        );

        const stack = target.querySelector('.casino-chip-stack');
        expect(stack).not.toBeNull();
        expect(stack.querySelector('.casino-chip-payout').textContent).toBe('+4000');
    });

    test('options.chipCount overrides the default scaling', () => {
        // Spy on the underlying primitive — chip insertion happens in
        // requestAnimationFrame inside flashChipsOn, so we can't assert
        // on the rendered .casino-chip count synchronously. Verifying
        // the helper passes the override through to flashWinPayout is
        // the clearer contract.
        const flashSpy = jest.spyOn(window.CasinoRender, 'flashWinPayout');
        try {
            window.TobyCasinoWinSettle.fire(
                { win: true, net: 50 }, target,
                { chipCount: () => 5 }
            );
            expect(flashSpy).toHaveBeenCalledTimes(1);
            const [, , chipCountArg] = flashSpy.mock.calls[0];
            expect(chipCountArg).toBe(5);
        } finally {
            flashSpy.mockRestore();
        }
    });

    test('default scaling: 3-chip floor on tiny wins, 7-chip cap on large', () => {
        const flashSpy = jest.spyOn(window.CasinoRender, 'flashWinPayout');
        try {
            // Tiny win — floor at 3 chips.
            window.TobyCasinoWinSettle.fire({ win: true, net: 50 }, target);
            expect(flashSpy.mock.calls[0][2]).toBe(3);

            // Huge win — capped at 7 chips by the helper's default formula.
            window.TobyCasinoWinSettle.fire({ win: true, net: 50000 }, target);
            expect(flashSpy.mock.calls[1][2]).toBe(7);
        } finally {
            flashSpy.mockRestore();
        }
    });

    test('null flashTarget skips the visual half but still plays sound', () => {
        window.TobyCasinoWinSettle.fire({ win: true, net: 100 }, null);

        expect(playSpy).toHaveBeenCalledWith('win');
        // Nothing to assert on the DOM — no target was provided.
    });

    test('missing CasinoSounds module silently no-ops the audio half', () => {
        delete window.CasinoSounds;
        // No sound module loaded — should not throw.
        expect(() => {
            window.TobyCasinoWinSettle.fire({ win: true, net: 100 }, target);
        }).not.toThrow();
        // Visual half still runs (CasinoRender is still loaded).
        expect(target.querySelector('.casino-chip-stack')).not.toBeNull();
    });

    test('null body is a silent no-op', () => {
        expect(() => {
            window.TobyCasinoWinSettle.fire(null, target);
        }).not.toThrow();
        expect(playSpy).not.toHaveBeenCalled();
    });
});

describe('TobyCasinoWinSettle.defaultChipCount', () => {
    test('scales with net by /100, floor 3, cap 7', () => {
        const f = window.TobyCasinoWinSettle.defaultChipCount;
        expect(f({ net: 50 })).toBe(3);   // floor
        expect(f({ net: 100 })).toBe(3);  // floor
        expect(f({ net: 300 })).toBe(3);
        expect(f({ net: 400 })).toBe(4);
        expect(f({ net: 700 })).toBe(7);  // cap
        expect(f({ net: 50000 })).toBe(7); // cap holds
    });

    test('jackpot payout overrides net when present', () => {
        const f = window.TobyCasinoWinSettle.defaultChipCount;
        expect(f({ net: 100, jackpotPayout: 700 })).toBe(7);
    });
});
