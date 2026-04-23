const { toggleInput, togglePlay, parseTimeInput, formatMs, validateClipMs } = require('../../main/resources/static/js/intros');

// ---------------------------------------------------------------------------
// toggleInput
// ---------------------------------------------------------------------------

describe('toggleInput', () => {
    beforeEach(() => {
        document.body.innerHTML = `
            <div id="urlSection" style="display:block"></div>
            <div id="fileSection" style="display:none"></div>
        `;
    });

    test('shows urlSection and hides fileSection when type is "url"', () => {
        toggleInput('url');
        expect(document.getElementById('urlSection').style.display).toBe('block');
        expect(document.getElementById('fileSection').style.display).toBe('none');
    });

    test('shows fileSection and hides urlSection when type is "file"', () => {
        toggleInput('file');
        expect(document.getElementById('urlSection').style.display).toBe('none');
        expect(document.getElementById('fileSection').style.display).toBe('block');
    });
});

// ---------------------------------------------------------------------------
// togglePlay
// ---------------------------------------------------------------------------

function makeAudio(id) {
    const audio = document.createElement('audio');
    audio.id = id;
    audio.src = `/music?id=${id}`;
    // JSDOM doesn't implement media APIs; provide stubs
    audio.play = jest.fn().mockResolvedValue(undefined);
    audio.pause = jest.fn();
    // Start paused
    Object.defineProperty(audio, 'paused', {
        get: jest.fn().mockReturnValue(true),
        configurable: true,
    });
    return audio;
}

function makeButton(audioId) {
    const btn = document.createElement('button');
    btn.className = 'btn-play';
    btn.dataset.audioId = audioId;
    btn.textContent = '▶';
    return btn;
}

describe('togglePlay — starting paused', () => {
    let audio, btn;

    beforeEach(() => {
        document.body.innerHTML = '';
        audio = makeAudio('audio-1');
        btn = makeButton('audio-1');
        document.body.appendChild(audio);
        document.body.appendChild(btn);
    });

    test('calls audio.play() when audio is paused', () => {
        togglePlay(btn);
        expect(audio.play).toHaveBeenCalled();
    });

    test('changes button text to ⏹ when starting playback', () => {
        togglePlay(btn);
        expect(btn.textContent).toBe('⏹');
    });

    test('adds "playing" class to button when starting playback', () => {
        togglePlay(btn);
        expect(btn.classList.contains('playing')).toBe(true);
    });
});

describe('togglePlay — starting playing', () => {
    let audio, btn;

    beforeEach(() => {
        document.body.innerHTML = '';
        audio = makeAudio('audio-1');
        // Override paused → false (currently playing)
        Object.defineProperty(audio, 'paused', {
            get: jest.fn().mockReturnValue(false),
            configurable: true,
        });
        btn = makeButton('audio-1');
        btn.textContent = '⏹';
        btn.classList.add('playing');
        document.body.appendChild(audio);
        document.body.appendChild(btn);
    });

    test('calls audio.pause() when audio is playing', () => {
        togglePlay(btn);
        expect(audio.pause).toHaveBeenCalled();
    });

    test('resets currentTime to 0 when stopping', () => {
        togglePlay(btn);
        expect(audio.currentTime).toBe(0);
    });

    test('changes button text back to ▶ when stopping', () => {
        togglePlay(btn);
        expect(btn.textContent).toBe('▶');
    });

    test('removes "playing" class from button when stopping', () => {
        togglePlay(btn);
        expect(btn.classList.contains('playing')).toBe(false);
    });
});

describe('togglePlay — stops other audio', () => {
    let audio1, audio2, btn1, btn2;

    beforeEach(() => {
        document.body.innerHTML = '';

        audio1 = makeAudio('audio-1');
        btn1 = makeButton('audio-1');

        audio2 = makeAudio('audio-2');
        // audio2 is currently playing
        Object.defineProperty(audio2, 'paused', {
            get: jest.fn().mockReturnValue(false),
            configurable: true,
        });
        btn2 = makeButton('audio-2');
        btn2.textContent = '⏹';
        btn2.classList.add('playing');

        document.body.appendChild(audio1);
        document.body.appendChild(btn1);
        document.body.appendChild(audio2);
        document.body.appendChild(btn2);
    });

    test('pauses all other audio elements before playing the new one', () => {
        togglePlay(btn1); // play audio-1 while audio-2 is "playing"
        expect(audio2.pause).toHaveBeenCalled();
    });

    test('resets all other play buttons to ▶ before playing the new one', () => {
        togglePlay(btn1);
        expect(btn2.textContent).toBe('▶');
        expect(btn2.classList.contains('playing')).toBe(false);
    });

    test('plays the clicked audio after stopping others', () => {
        togglePlay(btn1);
        expect(audio1.play).toHaveBeenCalled();
    });
});

// ---------------------------------------------------------------------------
// parseTimeInput
// ---------------------------------------------------------------------------

describe('parseTimeInput', () => {
    test('returns null for blank or nullish input', () => {
        expect(parseTimeInput('')).toBeNull();
        expect(parseTimeInput('   ')).toBeNull();
        expect(parseTimeInput(null)).toBeNull();
        expect(parseTimeInput(undefined)).toBeNull();
    });

    test('parses mm:ss format', () => {
        expect(parseTimeInput('0:00')).toBe(0);
        expect(parseTimeInput('0:07')).toBe(7000);
        expect(parseTimeInput('1:30')).toBe(90000);
        expect(parseTimeInput('0:07.5')).toBe(7500);
    });

    test('parses raw seconds', () => {
        expect(parseTimeInput('10')).toBe(10000);
        expect(parseTimeInput('1.5')).toBe(1500);
    });

    test('returns NaN for malformed input', () => {
        expect(Number.isNaN(parseTimeInput('abc'))).toBe(true);
        expect(Number.isNaN(parseTimeInput('1:2:3'))).toBe(true);
        expect(Number.isNaN(parseTimeInput('1:60'))).toBe(true); // seconds >= 60
        expect(Number.isNaN(parseTimeInput('-5'))).toBe(true);
    });
});

// ---------------------------------------------------------------------------
// formatMs
// ---------------------------------------------------------------------------

describe('formatMs', () => {
    test('formats integer-second values as m:ss', () => {
        expect(formatMs(0)).toBe('0:00');
        expect(formatMs(7000)).toBe('0:07');
        expect(formatMs(90000)).toBe('1:30');
    });

    test('formats sub-second values with one decimal', () => {
        expect(formatMs(7500)).toBe('0:07.5');
    });

    test('returns empty string for invalid input', () => {
        expect(formatMs(null)).toBe('');
        expect(formatMs(-1)).toBe('');
    });
});

// ---------------------------------------------------------------------------
// validateClipMs
// ---------------------------------------------------------------------------

describe('validateClipMs', () => {
    const MAX = 15 * 1000;

    test('accepts empty clip when source is short enough', () => {
        expect(validateClipMs(null, null, 10000, MAX)).toBe('');
    });

    test('rejects empty clip when source is longer than cap', () => {
        expect(validateClipMs(null, null, 60000, MAX)).toMatch(/too long/i);
    });

    test('accepts a tight clip on a long source', () => {
        expect(validateClipMs(10000, 22000, 120000, MAX)).toBe(''); // 12s
    });

    test('rejects clip longer than cap', () => {
        expect(validateClipMs(0, 16000, 60000, MAX)).toMatch(/too long/i);
    });

    test('rejects end <= start', () => {
        expect(validateClipMs(5000, 5000, 60000, MAX)).toMatch(/greater than start/i);
        expect(validateClipMs(5000, 3000, 60000, MAX)).toMatch(/greater than start/i);
    });

    test('rejects end beyond source duration', () => {
        expect(validateClipMs(0, 20000, 10000, MAX)).toMatch(/exceeds/i);
    });

    test('rejects negative start', () => {
        expect(validateClipMs(-1, 1000, 60000, MAX)).toMatch(/negative/i);
    });
});
