/**
 * @jest-environment node
 *
 * Runs under Node rather than the suite's usual jsdom environment: this file
 * builds its own JSDOM instances, and jsdom's own dependencies need Node's
 * globals (TextEncoder) that the jsdom environment doesn't expose.
 *
 * Loads the three magic.js layers the way a browser does — as real <script>
 * tags, in the order the template lists them — rather than the way the rest
 * of the suite does, via `require`.
 *
 * That distinction matters: each file picks its dependencies with
 * `typeof module !== 'undefined' ? require(...) : root.TobyCubeFormat`, and
 * Jest only ever takes the `require` branch. A typo in a global name, or a
 * file that quietly stopped publishing itself, would pass every other test
 * and break the page on load.
 */
const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const JS_DIR = path.join(__dirname, '../../main/resources/static/js');
const LAYERS = ['magic-format.js', 'magic-render.js', 'magic.js'];

/**
 * Runs the given files as scripts in a fresh document, in order. jsdom
 * reports a script that throws to its virtual console rather than to the
 * caller, so errors are collected onto the returned window.
 */
function loadInBrowser(files) {
    const errors = [];
    const dom = new JSDOM('<!doctype html><html><body></body></html>', { runScripts: 'dangerously' });
    dom.virtualConsole.on('jsdomError', (e) => errors.push(e));
    files.forEach((file) => {
        const script = dom.window.document.createElement('script');
        script.textContent = fs.readFileSync(path.join(JS_DIR, file), 'utf8');
        dom.window.document.body.appendChild(script);
    });
    dom.window.loadErrors = errors;
    return dom.window;
}

describe('the layered scripts in a browser', () => {
    test('each layer publishes itself under its own global', () => {
        const win = loadInBrowser(LAYERS);

        expect(typeof win.TobyCubeFormat).toBe('object');
        expect(typeof win.TobyCubeRender).toBe('object');
        expect(typeof win.TobyCube).toBe('object');
    });

    test('the entry point re-publishes the whole surface, as it did when it was one file', () => {
        const win = loadInBrowser(LAYERS);
        const viaRequire = require(path.join(JS_DIR, 'magic.js'));

        expect(Object.keys(win.TobyCube).sort()).toEqual(Object.keys(viaRequire).sort());
    });

    test('helpers work when reached through the browser globals', () => {
        const win = loadInBrowser(LAYERS);

        // One from each layer, to prove the cross-layer hand-off really resolved.
        expect(win.TobyCube.packHeader(1, 15)).toBe('== Pack 1 (15 cards) ==');
        expect(win.TobyCube.cardStatline('Instant', 1)).toBe('Instant · MV 1');
        const container = win.document.createElement('div');
        win.TobyCube.renderPacks(container, [[{ name: 'Bolt' }]]);
        expect(container.querySelectorAll('.cube-pack')).toHaveLength(1);
    });

    test('a renderer that calls down into the format layer still works', () => {
        const win = loadInBrowser(LAYERS);
        const container = win.document.createElement('div');

        // renderPacks -> cardCountLabel lives one layer down.
        win.TobyCube.renderPacks(container, [[{ name: 'Bolt' }]]);

        expect(container.querySelector('.cube-pack-count').textContent).toBe('1 card');
    });

    test('a correct load raises nothing', () => {
        expect(loadInBrowser(LAYERS).loadErrors).toEqual([]);
    });

    test('loading the layers out of order is what breaks — hence the template order', () => {
        // Guards the assumption the ordering test in MagicTemplateRenderTest
        // rests on: this isn't a stylistic preference, the page really dies.
        const win = loadInBrowser(['magic-render.js', 'magic-format.js', 'magic.js']);

        expect(win.loadErrors.length).toBeGreaterThan(0);
        expect(win.TobyCube).toBeUndefined();
    });
});
