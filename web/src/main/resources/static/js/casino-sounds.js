// Lightweight Web Audio sound effects for the casino games. No mp3/wav
// files — every cue is synthesised from oscillators so we don't ship
// (or licence) any audio assets. Loaded by the casino game pages
// alongside `casino-render.js`.
//
// API:
//   CasinoSounds.play('deal'|'chip'|'flip'|'win'|'lose'|'click')
//   CasinoSounds.toggle()           — flip enabled/disabled, persisted
//   CasinoSounds.isEnabled()        — current pref (default: enabled)
//   CasinoSounds.installToggle(parentEl)  — drop a 🔊/🔇 button somewhere
//
// Notes:
//   - Browsers block audio until the first user interaction, so the
//     module lazily creates its AudioContext on the first `play()`.
//     A user clicking "Deal" therefore unlocks audio for the session.
//   - Respects `prefers-reduced-motion: reduce` as a soft signal that
//     "fewer effects" is preferred → defaults sounds off in that case.
//   - The user pref is persisted in localStorage under
//     `tobybot.casino.sounds` ("on" | "off").
(function () {
    "use strict";

    var STORE_KEY = "tobybot.casino.sounds";
    var ctx = null;

    function reducedMotion() {
        try {
            return window.matchMedia &&
                window.matchMedia("(prefers-reduced-motion: reduce)").matches;
        } catch (_) {
            return false;
        }
    }

    function readPref() {
        try {
            var v = localStorage.getItem(STORE_KEY);
            if (v === "on") return true;
            if (v === "off") return false;
        } catch (_) { /* localStorage may be blocked */ }
        // Default: enabled, except when the OS asks for fewer effects.
        return !reducedMotion();
    }

    function writePref(on) {
        try { localStorage.setItem(STORE_KEY, on ? "on" : "off"); } catch (_) {}
    }

    var enabled = readPref();
    var listeners = [];

    function notify() {
        listeners.forEach(function (fn) { try { fn(enabled); } catch (_) {} });
    }

    function ensureCtx() {
        if (ctx) return ctx;
        var Ctor = window.AudioContext || window.webkitAudioContext;
        if (!Ctor) return null;
        try { ctx = new Ctor(); } catch (_) { ctx = null; }
        return ctx;
    }

    // Schedule a tiny decay envelope. `freq` can be a single number or a
    // function `(t) => freq` for a glide. `kind` is the oscillator type.
    function blip(opts) {
        if (!enabled) return;
        var c = ensureCtx();
        if (!c) return;
        var now = c.currentTime;
        var osc = c.createOscillator();
        var gain = c.createGain();
        osc.type = opts.kind || "sine";
        if (typeof opts.freq === "function") {
            // Sweep: setValueCurveAtTime expects a Float32Array of samples.
            var samples = 32;
            var arr = new Float32Array(samples);
            for (var i = 0; i < samples; i++) {
                arr[i] = opts.freq(i / (samples - 1));
            }
            osc.frequency.setValueCurveAtTime(arr, now, opts.dur);
        } else {
            osc.frequency.setValueAtTime(opts.freq, now);
        }
        var peak = opts.peak == null ? 0.18 : opts.peak;
        gain.gain.setValueAtTime(0, now);
        gain.gain.linearRampToValueAtTime(peak, now + 0.005);
        gain.gain.exponentialRampToValueAtTime(0.0001, now + opts.dur);
        osc.connect(gain).connect(c.destination);
        osc.start(now);
        osc.stop(now + opts.dur + 0.02);
    }

    // Pre-baked cues. Kept short and unobtrusive — these fire frequently
    // (every dealt card!) so they need to stay in the background.
    var cues = {
        click: function () { blip({ freq: 880, dur: 0.04, kind: "square", peak: 0.06 }); },
        deal:  function () { blip({ freq: function (t) { return 540 - 300 * t; }, dur: 0.08, kind: "triangle", peak: 0.08 }); },
        flip:  function () { blip({ freq: function (t) { return 720 - 320 * t; }, dur: 0.10, kind: "triangle", peak: 0.10 }); },
        chip:  function () { blip({ freq: 1200, dur: 0.06, kind: "sine", peak: 0.10 });
                              blip({ freq: 1600, dur: 0.04, kind: "sine", peak: 0.06 }); },
        // Win = rising 3-note arpeggio, scheduled out so it sounds like
        // a small fanfare without overlapping into a chord.
        win: function () {
            var c = ensureCtx();
            if (!c) return;
            var notes = [523.25, 659.25, 783.99]; // C5, E5, G5
            notes.forEach(function (f, i) {
                setTimeout(function () {
                    blip({ freq: f, dur: 0.18, kind: "triangle", peak: 0.14 });
                }, i * 110);
            });
        },
        // Lose = quick descending dip. Kept short and not too dour.
        lose: function () {
            blip({ freq: function (t) { return 220 - 80 * t; }, dur: 0.30, kind: "sawtooth", peak: 0.10 });
        },
    };

    function play(name) {
        var cue = cues[name];
        if (cue) cue();
    }

    function toggle() {
        enabled = !enabled;
        writePref(enabled);
        notify();
        if (enabled) play("click");
        return enabled;
    }

    function isEnabled() { return enabled; }

    // Floating mute toggle — drops a small 🔊/🔇 button in the bottom-
    // right of the page. Idempotent: calling twice doesn't stack.
    function installToggle(parentEl) {
        var host = parentEl || document.body;
        if (host.querySelector("[data-casino-sounds-toggle]")) return;
        var btn = document.createElement("button");
        btn.type = "button";
        btn.className = "casino-sound-toggle";
        btn.setAttribute("data-casino-sounds-toggle", "");
        btn.setAttribute("aria-label", enabled ? "Mute sound effects" : "Unmute sound effects");
        btn.textContent = enabled ? "🔊" : "🔇";
        btn.addEventListener("click", function () {
            toggle();
            btn.textContent = enabled ? "🔊" : "🔇";
            btn.setAttribute("aria-label", enabled ? "Mute sound effects" : "Unmute sound effects");
        });
        listeners.push(function (on) {
            btn.textContent = on ? "🔊" : "🔇";
        });
        host.appendChild(btn);
    }

    window.CasinoSounds = {
        play: play,
        toggle: toggle,
        isEnabled: isEnabled,
        installToggle: installToggle,
    };

    // Self-install the floating mute toggle. The script is only loaded
    // by casino game pages (each `<script src="/js/casino-sounds.js">`
    // opts in), so the toggle won't appear elsewhere. Wait for the body
    // to exist so we can append safely.
    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", function () { installToggle(); });
    } else {
        installToggle();
    }
})();
