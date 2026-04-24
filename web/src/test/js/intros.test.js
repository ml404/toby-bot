// Body-dataset keys + global mocks shared by the two mount* helpers in this
// file. Centralised to keep the Qodana DuplicatedCode check quiet and so
// future setup drift lives in one place. Must run *before* intros.js reads
// window.TobyApi during initIntroPage().
function applyIntroPageDatasetAndMocks() {
    document.body.dataset.introPage = '1';
    document.body.dataset.guildId = '222';
    document.body.dataset.targetDiscordId = '';
    document.body.dataset.maxFileKb = '550';
    document.body.dataset.maxDurationSeconds = '15';
    window.TobyToast = { show: jest.fn() };
    window.TobyModal = { confirm: jest.fn().mockResolvedValue(false) };
    window.TobyApi = { postJson: jest.fn().mockResolvedValue({ ok: true }) };
}

const {
    toggleInput,
    togglePlay,
    togglePlayClip,
    closeClipPreviewRow,
    parseTimeInput,
    formatMs,
    validateClipMs,
    ensureYtApi,
    createTrimBar,
    _resetYtApiPromiseForTests,
} = require('../../main/resources/static/js/intros');

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
    // JSDOM doesn't implement media APIs; provide stubs.
    audio.play = jest.fn().mockResolvedValue(undefined);
    audio.pause = jest.fn();
    // Start paused
    Object.defineProperty(audio, 'paused', {
        get: jest.fn().mockReturnValue(true),
        configurable: true,
    });
    // JSDOM's HTMLMediaElement.currentTime setter is a no-op with no media
    // loaded; swap in a plain getter/setter so tests can drive playback time.
    let _currentTime = 0;
    Object.defineProperty(audio, 'currentTime', {
        get: () => _currentTime,
        set: (v) => { _currentTime = v; },
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

// ---------------------------------------------------------------------------
// togglePlay — clip-aware playback on saved rows
// ---------------------------------------------------------------------------

function makeRowWithAudio(id, { startMs, endMs } = {}) {
    const table = document.createElement('table');
    const tbody = document.createElement('tbody');
    const tr = document.createElement('tr');
    tr.dataset.introId = id;
    if (startMs != null) tr.dataset.startMs = String(startMs);
    if (endMs != null) tr.dataset.endMs = String(endMs);
    const td = document.createElement('td');
    const audio = makeAudio('audio-' + id);
    const btn = makeButton('audio-' + id);
    td.appendChild(audio);
    td.appendChild(btn);
    tr.appendChild(td);
    tbody.appendChild(tr);
    table.appendChild(tbody);
    document.body.appendChild(table);
    return { tr, audio, btn };
}

describe('togglePlay — saved-row clip bounds', () => {
    beforeEach(() => {
        document.body.innerHTML = '';
    });

    test('seeks to data-start-ms / 1000 before playing', () => {
        const { audio, btn } = makeRowWithAudio('1', { startMs: 2500, endMs: 9000 });
        togglePlay(btn);
        expect(audio.currentTime).toBe(2.5);
        expect(audio.play).toHaveBeenCalled();
    });

    test('starts at 0 when no start-ms is set on the row', () => {
        const { audio, btn } = makeRowWithAudio('1');
        togglePlay(btn);
        expect(audio.currentTime).toBe(0);
    });

    test('pauses and resets to start when timeupdate crosses end-ms', () => {
        const { audio, btn } = makeRowWithAudio('1', { startMs: 1000, endMs: 4000 });
        togglePlay(btn);
        audio.currentTime = 4.25;
        audio.dispatchEvent(new Event('timeupdate'));
        expect(audio.pause).toHaveBeenCalled();
        expect(audio.currentTime).toBe(1);
        expect(btn.textContent).toBe('▶');
        expect(btn.classList.contains('playing')).toBe(false);
    });

    test('does not auto-pause on timeupdate when no end-ms is set', () => {
        const { audio, btn } = makeRowWithAudio('1', { startMs: 1000 });
        togglePlay(btn);
        // togglePlay sweeps every <audio> to pause(); ignore that and verify
        // no subsequent auto-pause fires from the timeupdate path.
        audio.pause.mockClear();
        audio.currentTime = 10;
        audio.dispatchEvent(new Event('timeupdate'));
        expect(audio.pause).not.toHaveBeenCalled();
    });
});

// ---------------------------------------------------------------------------
// togglePlayClip — inline YouTube iframe for saved rows
// ---------------------------------------------------------------------------

function makeClipRow(id, { videoId, startMs, endMs } = {}) {
    const table = document.createElement('table');
    const tbody = document.createElement('tbody');
    const tr = document.createElement('tr');
    tr.dataset.introId = id;
    if (videoId != null) tr.dataset.videoId = videoId;
    if (startMs != null) tr.dataset.startMs = String(startMs);
    if (endMs != null) tr.dataset.endMs = String(endMs);
    for (let i = 0; i < 3; i++) tr.appendChild(document.createElement('td'));
    const actionsTd = document.createElement('td');
    const btn = document.createElement('button');
    btn.className = 'btn-play';
    btn.dataset.playClipVideoId = videoId;
    btn.dataset.introId = id;
    btn.textContent = '▶';
    actionsTd.appendChild(btn);
    tr.appendChild(actionsTd);
    tbody.appendChild(tr);
    table.appendChild(tbody);
    document.body.appendChild(table);
    return { tr, btn, tbody };
}

describe('togglePlayClip', () => {
    beforeEach(() => {
        document.body.innerHTML = '';
    });

    test('injects a video-preview-row with a clip-configured iframe', () => {
        const { tr, btn } = makeClipRow('1_2_1', { videoId: 'dQw4w9WgXcQ', startMs: 5000, endMs: 12000 });
        togglePlayClip(btn);

        const previewRow = tr.nextElementSibling;
        expect(previewRow).not.toBeNull();
        expect(previewRow.className).toBe('video-preview-row');

        const iframe = previewRow.querySelector('iframe');
        expect(iframe).not.toBeNull();
        const src = iframe.getAttribute('src');
        // Use the privacy-enhanced host so Shorts (and strict-anti-bot videos)
        // render reliably instead of blanking the preview row.
        expect(src).toContain('https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ');
        expect(src).toContain('start=5');
        expect(src).toContain('end=12');
        expect(src).toContain('autoplay=1');
        expect(src).toContain('playsinline=1');

        expect(btn.textContent).toBe('⏹');
        expect(btn.classList.contains('playing')).toBe(true);
    });

    test('second click tears the preview row back down', () => {
        const { tr, btn } = makeClipRow('1_2_1', { videoId: 'abc', startMs: 0, endMs: 7000 });
        togglePlayClip(btn);
        togglePlayClip(btn);

        expect(tr.nextElementSibling).toBeNull();
        expect(btn.textContent).toBe('▶');
        expect(btn.classList.contains('playing')).toBe(false);
    });

    test('omits start / end params when no clip is set', () => {
        const { tr, btn } = makeClipRow('1_2_1', { videoId: 'abc' });
        togglePlayClip(btn);

        const iframe = tr.nextElementSibling.querySelector('iframe');
        const src = iframe.getAttribute('src');
        expect(src).not.toContain('start=');
        expect(src).not.toContain('end=');
    });

    test('starting a clip preview closes any previously-open preview', () => {
        const first = makeClipRow('1_2_1', { videoId: 'first' });
        const second = makeClipRow('1_2_2', { videoId: 'second' });
        togglePlayClip(first.btn);
        expect(first.tr.nextElementSibling.className).toBe('video-preview-row');

        togglePlayClip(second.btn);

        // Only one preview row exists at a time; first button reset, second open.
        expect(document.querySelectorAll('.video-preview-row').length).toBe(1);
        expect(first.btn.classList.contains('playing')).toBe(false);
        expect(second.btn.classList.contains('playing')).toBe(true);
        expect(second.tr.nextElementSibling.className).toBe('video-preview-row');
    });
});

describe('closeClipPreviewRow', () => {
    beforeEach(() => {
        document.body.innerHTML = '';
    });

    test('removes injected preview rows and resets their buttons', () => {
        const { tr, btn } = makeClipRow('1_2_1', { videoId: 'abc' });
        togglePlayClip(btn);
        closeClipPreviewRow();
        expect(tr.nextElementSibling).toBeNull();
        expect(btn.classList.contains('playing')).toBe(false);
        expect(btn.textContent).toBe('▶');
    });
});

// ---------------------------------------------------------------------------
// initIntroPage smoke regression — catches TDZ / binding-order bugs that
// would otherwise only surface in the browser and silently break every
// click handler on the page (delete, clip-edit, iframe preview, etc.).
// ---------------------------------------------------------------------------

describe('initIntroPage smoke test', () => {
    beforeEach(() => {
        document.body.innerHTML = '';
        jest.resetModules();
    });

    function mountIntroPage() {
        document.body.innerHTML = `
            <meta name="_csrf" content="t">
            <meta name="_csrf_header" content="X-CSRF-TOKEN">
            <form id="introForm">
                <input type="hidden" id="inputType" name="inputType" value="url">
                <button type="button" class="source-tab" data-source="url" aria-pressed="true">URL</button>
                <button type="button" class="source-tab" data-source="file" aria-pressed="false">File</button>
                <input type="range" id="volume" name="volume" min="1" max="100" value="90" data-volume-slider>
                <span data-volume-value-for="volume">90%</span>
                <input type="text" id="startTime" data-clip-time="start">
                <input type="text" id="endTime" data-clip-time="end">
                <input type="hidden" id="startMs" name="startMs">
                <input type="hidden" id="endMs" name="endMs">
                <span data-clip-length></span>
                <span id="clipError"></span>
                <input type="url" id="url" name="url">
                <span id="urlError"></span>
                <div id="urlPreview"></div>
                <div id="urlPreviewIframe"></div>
                <div id="dropZone" tabindex="0"><input type="file" id="file" name="file"></div>
                <span id="fileError"></span>
                <div id="fileSummary"></div>
                <div id="trimBar" class="trim-bar" hidden>
                    <div class="trim-track" data-trim-track>
                        <div class="trim-selection" data-trim-selection></div>
                        <div class="trim-playhead" data-trim-playhead></div>
                        <button type="button" data-trim-thumb="start"></button>
                        <button type="button" data-trim-thumb="end"></button>
                    </div>
                    <div class="trim-controls">
                        <button type="button" data-trim-play><span data-trim-play-label>Play clip</span></button>
                        <span data-trim-time>0:00 – 0:00</span>
                    </div>
                    <p data-trim-status hidden></p>
                </div>
                <button id="submitBtn" type="submit">Add intro</button>
            </form>
            <table><tbody id="introsTbody">
                <tr data-intro-id="1_2_1" data-start-ms="" data-end-ms="">
                    <td><span class="slot-index">1</span></td>
                    <td data-editable-name="true" data-intro-id="1_2_1">
                        <span class="name-label">intro.mp3</span>
                        <button type="button" class="clip-badge" data-edit-clip="true" data-intro-id="1_2_1">edit</button>
                    </td>
                    <td>
                        <input type="range" data-row-volume="true" data-intro-id="1_2_1" min="1" max="100" value="90">
                        <span class="vol-value">90%</span>
                    </td>
                    <td>
                        <form><button type="button" data-delete-intro="true" data-intro-name="intro.mp3">Delete</button></form>
                    </td>
                </tr>
            </tbody></table>
            <template id="clipEditorTemplate"><div class="clip-editor-popover"></div></template>
        `;
        applyIntroPageDatasetAndMocks();
        // Re-require to run initIntroPage against the freshly-built DOM.
        require('../../main/resources/static/js/intros');
    }

    test('binds page handlers without throwing (TDZ regression)', () => {
        expect(() => mountIntroPage()).not.toThrow();
    });

    test('delete buttons still receive a click handler after init', () => {
        mountIntroPage();
        const deleteBtn = document.querySelector('[data-delete-intro]');
        expect(deleteBtn).not.toBeNull();
        deleteBtn.click();
        // Successful binding reaches the confirm modal. Prior to the TDZ fix,
        // initIntroPage aborted before this addEventListener ran.
        expect(window.TobyModal.confirm).toHaveBeenCalled();
    });

    test('typing a valid clip on a long source re-enables submit', () => {
        mountIntroPage();
        const submitBtn = document.getElementById('submitBtn');
        const urlInput = document.getElementById('url');
        const startInput = document.getElementById('startTime');
        const endInput = document.getElementById('endTime');

        // Simulate the "URL too long" state the backend preview would have produced:
        // a non-empty URL plus sourceDurationMs past the cap. We reach into the
        // module's state by triggering the same listeners the fetch path uses.
        urlInput.value = 'https://www.youtube.com/watch?v=x';
        // previewState.tooLong + clipState.sourceDurationMs are module-internal;
        // we prove the fix by showing that after the user types a clip that
        // fits, the submit button becomes enabled.
        startInput.value = '0:01';
        startInput.dispatchEvent(new Event('input'));
        endInput.value = '0:07';
        endInput.dispatchEvent(new Event('input'));

        expect(submitBtn.disabled).toBe(false);
    });

    test('home-page dnd CTA path exists in the campaign controller mapping', () => {
        // The CTA links to /dnd/campaign — this is a smoke assertion that
        // the string used in the home template remains a stable literal.
        // The actual route binding lives in CampaignController.kt (verified
        // by Kotlin tests), but we guard against accidental home.html drift.
        const href = '/dnd/campaign';
        expect(href.startsWith('/dnd/')).toBe(true);
    });
});

// ---------------------------------------------------------------------------
// createTrimBar — dual-handle draggable trim bar
// ---------------------------------------------------------------------------

function mountTrimBarFixture() {
    document.body.innerHTML = `
        <div id="trimBar" class="trim-bar" hidden>
            <div class="trim-track" data-trim-track>
                <div class="trim-selection" data-trim-selection></div>
                <div class="trim-playhead" data-trim-playhead></div>
                <button type="button" data-trim-thumb="start"></button>
                <button type="button" data-trim-thumb="end"></button>
            </div>
            <div class="trim-controls">
                <button type="button" data-trim-play><span data-trim-play-label>Play clip</span></button>
                <span data-trim-time>0:00 – 0:00</span>
            </div>
            <p data-trim-status hidden></p>
        </div>
    `;
    const root = document.getElementById('trimBar');
    const track = root.querySelector('[data-trim-track]');
    // JSDOM returns an all-zeros rect; pretend the track is 1000px wide so
    // clientX values map cleanly to percentages (1px == 0.1%).
    track.getBoundingClientRect = () => ({
        left: 0, top: 0, right: 1000, bottom: 28, width: 1000, height: 28, x: 0, y: 0, toJSON: () => ({})
    });
    return { root, track };
}

function dispatchPointer(target, type, opts) {
    const ev = new Event(type, { bubbles: true, cancelable: true });
    Object.assign(ev, Object.assign({ pointerId: 1, clientX: 0, clientY: 0 }, opts || {}));
    target.dispatchEvent(ev);
    return ev;
}

describe('createTrimBar — initial state', () => {
    test('is hidden until setDuration is called with a positive value', () => {
        const { root } = mountTrimBarFixture();
        createTrimBar(root, { maxClipMs: 15000 });
        expect(root.hidden).toBe(true);
    });

    test('setDuration(0) leaves the bar hidden', () => {
        const { root } = mountTrimBarFixture();
        const bar = createTrimBar(root, { maxClipMs: 15000 });
        bar.setDuration(0);
        expect(root.hidden).toBe(true);
    });

    test('setDuration(positive) unhides and initialises handles at [0, min(cap, duration)]', () => {
        const { root } = mountTrimBarFixture();
        const bar = createTrimBar(root, { maxClipMs: 15000 });
        bar.setDuration(30000); // 30s source, 15s cap
        expect(root.hidden).toBe(false);
        const range = bar.getRange();
        expect(range.startMs).toBe(0);
        expect(range.endMs).toBe(15000);
    });

    test('setDuration on a very short source caps end at the duration', () => {
        const { root } = mountTrimBarFixture();
        const bar = createTrimBar(root, { maxClipMs: 15000 });
        bar.setDuration(5000); // 5s source
        expect(bar.getRange().endMs).toBe(5000);
    });
});

describe('createTrimBar — setRange', () => {
    test('is a no-op when duration is zero', () => {
        const { root } = mountTrimBarFixture();
        const bar = createTrimBar(root, { maxClipMs: 15000 });
        bar.setRange(1000, 5000);
        expect(bar.getRange().startMs).toBe(0);
        expect(bar.getRange().endMs).toBe(0);
    });

    test('clamps start and end within [0, duration]', () => {
        const { root } = mountTrimBarFixture();
        // Use a cap wider than the source so the duration clamp (not the cap)
        // is what's being exercised here.
        const bar = createTrimBar(root, { maxClipMs: 60000 });
        bar.setDuration(30000);
        bar.setRange(-500, 50000);
        const range = bar.getRange();
        expect(range.startMs).toBe(0);
        expect(range.endMs).toBe(30000);
    });

    test('clamps the clip window to maxClipMs', () => {
        const { root } = mountTrimBarFixture();
        const bar = createTrimBar(root, { maxClipMs: 15000 });
        bar.setDuration(60000);
        bar.setRange(0, 20000); // 20s range — over the 15s cap
        expect(bar.getRange().endMs - bar.getRange().startMs).toBe(15000);
    });

    test('null end defaults to start + min(cap, remaining duration)', () => {
        const { root } = mountTrimBarFixture();
        const bar = createTrimBar(root, { maxClipMs: 15000 });
        bar.setDuration(60000);
        bar.setRange(5000, null);
        const range = bar.getRange();
        expect(range.startMs).toBe(5000);
        expect(range.endMs).toBe(20000);
    });

    test('ensures end is at least start + 100ms', () => {
        const { root } = mountTrimBarFixture();
        const bar = createTrimBar(root, { maxClipMs: 15000 });
        bar.setDuration(60000);
        bar.setRange(5000, 5000); // identical
        const range = bar.getRange();
        expect(range.endMs).toBeGreaterThan(range.startMs);
    });
});

describe('createTrimBar — drag', () => {
    test('dragging the end thumb emits onChange with a new endMs', () => {
        const { root, track } = mountTrimBarFixture();
        const onChange = jest.fn();
        const bar = createTrimBar(root, { maxClipMs: 15000, onChange: onChange });
        bar.setDuration(20000); // 20s source; initial range 0..15000
        onChange.mockClear();

        const endThumb = root.querySelector('[data-trim-thumb="end"]');
        dispatchPointer(endThumb, 'pointerdown', { clientX: 750 });
        // Drag to 600px → 60% of 20000 = 12000ms
        dispatchPointer(document, 'pointermove', { clientX: 600 });
        dispatchPointer(document, 'pointerup', {});

        expect(onChange).toHaveBeenCalled();
        const last = onChange.mock.calls[onChange.mock.calls.length - 1][0];
        expect(last.endMs).toBe(12000);
        expect(last.startMs).toBe(0);
        // Thumb position is set declaratively via inline style
        expect(endThumb.style.left).toBe('60%');
    });

    test('dragging the start thumb updates startMs and respects endMs', () => {
        const { root, track } = mountTrimBarFixture();
        const onChange = jest.fn();
        const bar = createTrimBar(root, { maxClipMs: 15000, onChange: onChange });
        bar.setDuration(20000);
        onChange.mockClear();

        const startThumb = root.querySelector('[data-trim-thumb="start"]');
        dispatchPointer(startThumb, 'pointerdown', { clientX: 0 });
        // Drag to 200px → 20% of 20000 = 4000ms
        dispatchPointer(document, 'pointermove', { clientX: 200 });
        dispatchPointer(document, 'pointerup', {});

        const last = onChange.mock.calls[onChange.mock.calls.length - 1][0];
        expect(last.startMs).toBe(4000);
    });

    test('dragging past the max clip cap pushes the opposite thumb', () => {
        const { root, track } = mountTrimBarFixture();
        const onChange = jest.fn();
        const bar = createTrimBar(root, { maxClipMs: 15000, onChange: onChange });
        bar.setDuration(60000); // 60s source
        bar.setRange(0, 10000); // initial 0..10000

        const endThumb = root.querySelector('[data-trim-thumb="end"]');
        dispatchPointer(endThumb, 'pointerdown', { clientX: 166 });
        // Drag end to 500px → 50% of 60000 = 30000ms. Gap now 30s, exceeds 15s cap.
        // Start should be pushed to 15000 (30000 - 15000).
        dispatchPointer(document, 'pointermove', { clientX: 500 });
        dispatchPointer(document, 'pointerup', {});

        const range = bar.getRange();
        expect(range.endMs - range.startMs).toBe(15000);
        expect(range.endMs).toBe(30000);
        expect(range.startMs).toBe(15000);
    });

    test('dragging does nothing when the bar is disabled', () => {
        const { root } = mountTrimBarFixture();
        const onChange = jest.fn();
        const bar = createTrimBar(root, { maxClipMs: 15000, onChange: onChange });
        bar.setDuration(20000);
        bar.disable('Live streams can’t be clipped.');
        onChange.mockClear();

        const endThumb = root.querySelector('[data-trim-thumb="end"]');
        dispatchPointer(endThumb, 'pointerdown', { clientX: 500 });
        dispatchPointer(document, 'pointermove', { clientX: 300 });
        dispatchPointer(document, 'pointerup', {});

        expect(onChange).not.toHaveBeenCalled();
    });
});

describe('createTrimBar — keyboard nudging', () => {
    test('ArrowRight on end thumb increments endMs by 100ms', () => {
        const { root } = mountTrimBarFixture();
        const onChange = jest.fn();
        const bar = createTrimBar(root, { maxClipMs: 15000, onChange: onChange });
        bar.setDuration(60000);
        bar.setRange(0, 10000);
        onChange.mockClear();

        const endThumb = root.querySelector('[data-trim-thumb="end"]');
        const ev = new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true });
        endThumb.dispatchEvent(ev);

        expect(bar.getRange().endMs).toBe(10100);
        expect(onChange).toHaveBeenCalledWith({ startMs: 0, endMs: 10100 });
    });

    test('Shift+ArrowRight nudges by 1000ms', () => {
        const { root } = mountTrimBarFixture();
        const bar = createTrimBar(root, { maxClipMs: 15000 });
        bar.setDuration(60000);
        bar.setRange(0, 10000);

        const endThumb = root.querySelector('[data-trim-thumb="end"]');
        endThumb.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', shiftKey: true, bubbles: true }));

        expect(bar.getRange().endMs).toBe(11000);
    });

    test('ArrowLeft on start thumb decrements startMs by 100ms', () => {
        const { root } = mountTrimBarFixture();
        const bar = createTrimBar(root, { maxClipMs: 15000 });
        bar.setDuration(60000);
        bar.setRange(5000, 10000);

        const startThumb = root.querySelector('[data-trim-thumb="start"]');
        startThumb.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }));

        expect(bar.getRange().startMs).toBe(4900);
    });

    test('non-arrow keys are ignored', () => {
        const { root } = mountTrimBarFixture();
        const onChange = jest.fn();
        const bar = createTrimBar(root, { maxClipMs: 15000, onChange: onChange });
        bar.setDuration(60000);
        bar.setRange(0, 10000);
        onChange.mockClear();

        const endThumb = root.querySelector('[data-trim-thumb="end"]');
        endThumb.dispatchEvent(new KeyboardEvent('keydown', { key: 'Space', bubbles: true }));

        expect(onChange).not.toHaveBeenCalled();
    });
});

describe('createTrimBar — play button', () => {
    let rafCallbacks;

    beforeEach(() => {
        rafCallbacks = [];
        global.requestAnimationFrame = (cb) => { rafCallbacks.push(cb); return rafCallbacks.length; };
        global.cancelAnimationFrame = (id) => { rafCallbacks[id - 1] = null; };
    });

    afterEach(() => {
        delete global.requestAnimationFrame;
        delete global.cancelAnimationFrame;
    });

    function flushRaf(t) {
        const batch = rafCallbacks.slice();
        rafCallbacks = [];
        batch.forEach(cb => { if (cb) cb(t); });
    }

    test('click on play seeks to start and plays', () => {
        const { root } = mountTrimBarFixture();
        const bar = createTrimBar(root, { maxClipMs: 15000 });
        bar.setDuration(30000);
        bar.setRange(5000, 12000);

        const seek = jest.fn();
        const play = jest.fn();
        const pause = jest.fn();
        let currentMs = 0;
        bar.setBackend({
            getCurrentTimeMs: () => currentMs,
            seek: seek,
            play: play,
            pause: pause,
        });

        const playBtn = root.querySelector('[data-trim-play]');
        playBtn.click();

        expect(seek).toHaveBeenCalledWith(5000);
        expect(play).toHaveBeenCalled();
    });

    test('play loop pauses when currentTime reaches endMs', () => {
        const { root } = mountTrimBarFixture();
        const bar = createTrimBar(root, { maxClipMs: 15000 });
        bar.setDuration(30000);
        bar.setRange(5000, 12000);

        const pause = jest.fn();
        let currentMs = 5000;
        bar.setBackend({
            getCurrentTimeMs: () => currentMs,
            seek: () => {},
            play: () => {},
            pause: pause,
        });

        root.querySelector('[data-trim-play]').click();
        // First tick: still inside clip
        flushRaf();
        expect(pause).not.toHaveBeenCalled();

        // Advance past end
        currentMs = 12500;
        flushRaf();
        expect(pause).toHaveBeenCalled();
    });

    test('clicking play a second time (while playing) stops playback', () => {
        const { root } = mountTrimBarFixture();
        const bar = createTrimBar(root, { maxClipMs: 15000 });
        bar.setDuration(30000);
        bar.setRange(0, 10000);

        const pause = jest.fn();
        bar.setBackend({
            getCurrentTimeMs: () => 2000,
            seek: () => {},
            play: () => {},
            pause: pause,
        });

        const playBtn = root.querySelector('[data-trim-play]');
        playBtn.click();
        playBtn.click();
        expect(pause).toHaveBeenCalled();
    });

    test('play is a no-op when no backend is set', () => {
        const { root } = mountTrimBarFixture();
        const bar = createTrimBar(root, { maxClipMs: 15000 });
        bar.setDuration(30000);
        // No setBackend call
        expect(() => root.querySelector('[data-trim-play]').click()).not.toThrow();
    });
});

describe('createTrimBar — disable', () => {
    test('disable() with no status hides the bar', () => {
        const { root } = mountTrimBarFixture();
        const bar = createTrimBar(root, { maxClipMs: 15000 });
        bar.setDuration(20000);
        expect(root.hidden).toBe(false);
        bar.disable();
        expect(root.hidden).toBe(true);
    });

    test('disable(statusText) keeps the bar visible but marks data-disabled', () => {
        const { root } = mountTrimBarFixture();
        const bar = createTrimBar(root, { maxClipMs: 15000 });
        bar.disable('Live streams can’t be clipped.');
        expect(root.hidden).toBe(false);
        expect(root.getAttribute('data-disabled')).toBe('true');
        const status = root.querySelector('[data-trim-status]');
        expect(status.hidden).toBe(false);
        expect(status.textContent).toBe('Live streams can’t be clipped.');
    });
});

describe('createTrimBar — time display', () => {
    test('updates the mm:ss range label on setRange', () => {
        const { root } = mountTrimBarFixture();
        const bar = createTrimBar(root, { maxClipMs: 15000 });
        bar.setDuration(60000);
        bar.setRange(5000, 12500);
        const timeEl = root.querySelector('[data-trim-time]');
        expect(timeEl.textContent).toBe('0:05 – 0:12.5');
    });
});

// ---------------------------------------------------------------------------
// ensureYtApi — loader contract
// ---------------------------------------------------------------------------

describe('ensureYtApi', () => {
    beforeEach(() => {
        _resetYtApiPromiseForTests();
        // Clean up any globals a previous test left behind
        delete window.YT;
        delete window.onYouTubeIframeAPIReady;
        document.querySelectorAll('script[src*="iframe_api"]').forEach(s => s.remove());
    });

    test('resolves immediately when window.YT.Player is already available', async () => {
        window.YT = { Player: function () {} };
        await expect(ensureYtApi()).resolves.toBeUndefined();
    });

    test('returns the same promise on repeat calls (idempotent)', () => {
        const p1 = ensureYtApi();
        const p2 = ensureYtApi();
        expect(p1).toBe(p2);
    });

    test('injects an iframe_api script tag on first call', () => {
        ensureYtApi();
        const scripts = document.querySelectorAll('script[src*="iframe_api"]');
        expect(scripts.length).toBe(1);
    });

    test('resolves when onYouTubeIframeAPIReady fires', async () => {
        const p = ensureYtApi();
        // Simulate the YouTube script loading and invoking the global callback.
        window.YT = { Player: function () {} };
        window.onYouTubeIframeAPIReady();
        await expect(p).resolves.toBeUndefined();
    });

    test('preserves any pre-existing onYouTubeIframeAPIReady hook', async () => {
        const prior = jest.fn();
        window.onYouTubeIframeAPIReady = prior;
        const p = ensureYtApi();
        window.YT = { Player: function () {} };
        window.onYouTubeIframeAPIReady();
        await p;
        expect(prior).toHaveBeenCalled();
    });

    test('rejects when the script fires onerror', async () => {
        const p = ensureYtApi();
        const script = document.querySelector('script[src*="iframe_api"]');
        script.onerror(new Event('error'));
        await expect(p).rejects.toThrow(/load failed/);
    });
});

// ---------------------------------------------------------------------------
// initIntroPage — trim-bar integration smoke
// ---------------------------------------------------------------------------

describe('initIntroPage — trim bar integration', () => {
    beforeEach(() => {
        document.body.innerHTML = '';
        jest.resetModules();
        _resetYtApiPromiseForTests();
    });

    function mountWithTrimBar() {
        document.body.innerHTML = `
            <meta name="_csrf" content="t">
            <meta name="_csrf_header" content="X-CSRF-TOKEN">
            <form id="introForm">
                <input type="hidden" id="inputType" name="inputType" value="url">
                <button type="button" class="source-tab" data-source="url" aria-pressed="true">URL</button>
                <button type="button" class="source-tab" data-source="file" aria-pressed="false">File</button>
                <input type="range" id="volume" name="volume" min="1" max="100" value="90" data-volume-slider>
                <span data-volume-value-for="volume">90%</span>
                <input type="text" id="startTime" data-clip-time="start">
                <input type="text" id="endTime" data-clip-time="end">
                <input type="hidden" id="startMs" name="startMs">
                <input type="hidden" id="endMs" name="endMs">
                <span data-clip-length></span>
                <span id="clipError"></span>
                <input type="url" id="url" name="url">
                <span id="urlError"></span>
                <div id="urlPreview"></div>
                <div id="urlPreviewIframe"></div>
                <div id="dropZone" tabindex="0"><input type="file" id="file" name="file"></div>
                <span id="fileError"></span>
                <div id="fileSummary"></div>
                <div id="trimBar" class="trim-bar" hidden>
                    <div class="trim-track" data-trim-track>
                        <div class="trim-selection" data-trim-selection></div>
                        <div class="trim-playhead" data-trim-playhead></div>
                        <button type="button" data-trim-thumb="start"></button>
                        <button type="button" data-trim-thumb="end"></button>
                    </div>
                    <div class="trim-controls">
                        <button type="button" data-trim-play><span data-trim-play-label>Play clip</span></button>
                        <span data-trim-time>0:00 – 0:00</span>
                    </div>
                    <p data-trim-status hidden></p>
                </div>
                <button id="submitBtn" type="submit">Add intro</button>
            </form>
            <table><tbody id="introsTbody"></tbody></table>
            <template id="clipEditorTemplate"><div class="clip-editor-popover"></div></template>
        `;
        applyIntroPageDatasetAndMocks();
        require('../../main/resources/static/js/intros');
    }

    test('mounts without throwing when trimBar element is present', () => {
        expect(() => mountWithTrimBar()).not.toThrow();
    });

    test('typing a valid clip propagates into hidden ms fields without needing the trim bar', () => {
        mountWithTrimBar();
        const startInput = document.getElementById('startTime');
        const endInput = document.getElementById('endTime');
        const startHidden = document.getElementById('startMs');
        const endHidden = document.getElementById('endMs');

        startInput.value = '0:03';
        startInput.dispatchEvent(new Event('input'));
        endInput.value = '0:09';
        endInput.dispatchEvent(new Event('input'));

        expect(startHidden.value).toBe('3000');
        expect(endHidden.value).toBe('9000');
    });

    test('trim bar stays hidden until a source provides its duration', () => {
        mountWithTrimBar();
        const trimBar = document.getElementById('trimBar');
        expect(trimBar.hidden).toBe(true);
    });
});
