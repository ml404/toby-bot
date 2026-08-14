// Behavioural browser tests for the Magic toolkit (/magic).
//
// The rest of the e2e suite checks layout at four viewports; this one drives
// the page. It runs against fixtures/magic.html — the real template's render,
// with the real CSS and the real three-layer JS — and stubs /magic/api/* so
// nothing reaches Scryfall or a database.
//
// What this covers that the Jest suite can't: the scripts actually loading in
// a browser in the order the template lists them, the wiring binding to the
// real markup (every `data-` hook a selector depends on), and the download →
// re-import round trip going through an actual file.
const { test, expect } = require('@playwright/test');

/** A card as /magic/api/* returns it. */
function card(name) {
    return { name, imageUrl: null, imageUrlLarge: null, typeLine: 'Instant', manaValue: 1 };
}

/** Two packs of two, the shape both the generate and packs endpoints return. */
const DEALT = {
    ok: true,
    poolSize: 4,
    packCount: 2,
    packSize: 2,
    packs: [
        [card('Lightning Bolt'), card('Shock')],
        [card('Forest'), card('Island')],
    ],
    distribution: [{ category: 'Red', count: 2, asFan: 1 }],
    notFound: [],
};

/**
 * Stubs the JSON API. [overrides] maps a URL fragment to the body to return,
 * and every stubbed call is recorded so a test can assert what the page asked
 * for. Anything unstubbed 404s loudly rather than silently hanging.
 */
async function stubApi(page, overrides = {}) {
    const calls = [];
    await page.route('**/magic/api/**', async (route) => {
        const url = route.request().url();
        calls.push(url);
        const match = Object.keys(overrides).find((fragment) => url.includes(fragment));
        if (!match) return route.fulfill({ status: 404, body: '{"ok":false,"error":"not stubbed"}' });
        const body = overrides[match];
        await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify(typeof body === 'function' ? body(route.request()) : body),
        });
    });
    return calls;
}

/** Fails the test on any uncaught page error, which a broken script load is. */
function failOnPageErrors(page) {
    const errors = [];
    page.on('pageerror', (e) => errors.push(e.message));
    return errors;
}

test.describe('/magic', () => {
    test('loads its three script layers and wires the page up', async ({ page }) => {
        const errors = failOnPageErrors(page);
        await page.goto('/magic.html');

        // Each layer publishes itself; the entry point republishes the lot.
        const globals = await page.evaluate(() => ({
            format: typeof window.TobyCubeFormat,
            render: typeof window.TobyCubeRender,
            all: typeof window.TobyCube,
            exports: Object.keys(window.TobyCube || {}).length,
        }));

        expect(errors).toEqual([]);
        expect(globals).toMatchObject({ format: 'object', render: 'object', all: 'object' });
        expect(globals.exports).toBeGreaterThan(50);
    });

    test('opens on card lookup, and the tabs switch panels', async ({ page }) => {
        await page.goto('/magic.html');

        await expect(page.locator('#panel-card')).toBeVisible();
        await expect(page.locator('#panel-generate')).toBeHidden();

        await page.click('[data-tab="generate"]');

        await expect(page.locator('#panel-generate')).toBeVisible();
        await expect(page.locator('#panel-card')).toBeHidden();
    });

    test('a #generate deep link opens that tab on load', async ({ page }) => {
        await page.goto('/magic.html#generate');

        await expect(page.locator('#panel-generate')).toBeVisible();
    });

    test('generates packs from a Scryfall query and shows the cards', async ({ page }) => {
        const calls = await stubApi(page, { '/magic/api/generate': DEALT });
        await page.goto('/magic.html#generate');

        await page.fill('[data-source-panel="search"] input[name="query"]', 'c:r');
        await page.fill('[data-form="generate"] input[name="packs"]', '2');
        await page.fill('[data-form="generate"] input[name="packSize"]', '2');
        await page.click('[data-form="generate"] button[type="submit"]');

        await expect(page.locator('.cube-pack')).toHaveCount(2);
        await expect(page.locator('.cube-pack').first().locator('.cube-pack-count')).toHaveText('2 cards');
        await expect(page.locator('[data-summary="generate"]')).toContainText('Dealt 2 packs of 2');
        expect(calls.some((u) => u.includes('query=c%3Ar'))).toBe(true);
    });

    test('an API error is shown rather than leaving the page silent', async ({ page }) => {
        await page.route('**/magic/api/generate**', (route) =>
            route.fulfill({
                status: 400,
                contentType: 'application/json',
                body: JSON.stringify({ ok: false, error: 'Not enough cards: need 360.' }),
            }));
        await page.goto('/magic.html#generate');

        await page.fill('[data-source-panel="search"] input[name="query"]', 'c:r');
        await page.click('[data-form="generate"] button[type="submit"]');

        await expect(page.locator('[data-status="generate"]')).toContainText('Not enough cards');
        await expect(page.locator('.cube-pack')).toHaveCount(0);
    });

    test('the dealt packs download as a pack list', async ({ page }) => {
        await stubApi(page, { '/magic/api/generate': DEALT });
        await page.goto('/magic.html#generate');
        await page.fill('[data-source-panel="search"] input[name="query"]', 'c:r');
        await page.click('[data-form="generate"] button[type="submit"]');
        await expect(page.locator('.cube-pack')).toHaveCount(2);

        const download = await Promise.all([
            page.waitForEvent('download'),
            page.click('[data-download="generate"]'),
        ]).then(([d]) => d);

        expect(download.suggestedFilename()).toBe('cube-packs.txt');
        const body = await download.createReadStream().then(streamToString);
        expect(body).toContain('== Pack 1 (2 cards) ==');
        expect(body).toContain('  Lightning Bolt');
        expect(body).toMatch(/^# c:r — 2 packs of 2 — \d{4}-\d{2}-\d{2}/);
    });

    test('a downloaded pack list loads back in as the same packs', async ({ page }) => {
        // The whole point of the feature, driven end to end in a browser: the
        // file the page wrote goes back through the file picker and the API.
        const posted = [];
        await page.route('**/magic/api/packs', async (route) => {
            posted.push(JSON.parse(route.request().postData()));
            await route.fulfill({
                status: 200,
                contentType: 'application/json',
                body: JSON.stringify({ ...DEALT, provenance: '# c:r — 2 packs of 2 — 2026-08-09' }),
            });
        });
        await page.goto('/magic.html#generate');

        const file = [
            '# c:r — 2 packs of 2 — 2026-08-09',
            '',
            '== Pack 1 (2 cards) ==',
            '  Lightning Bolt',
            '  Shock',
            '',
            '== Pack 2 (2 cards) ==',
            '  Forest',
            '  Island',
            '',
        ].join('\n');
        await page.setInputFiles('[data-import-file="generate"]', {
            name: 'cube-packs.txt',
            mimeType: 'text/plain',
            buffer: Buffer.from(file),
        });

        await expect(page.locator('.cube-pack')).toHaveCount(2);
        await expect(page.locator('[data-summary="generate"]')).toContainText('Loaded 2 packs');
        // The file reached the server verbatim — headers, provenance and all.
        expect(posted[0].text).toContain('== Pack 1 (2 cards) ==');
        expect(posted[0].text).toContain('# c:r — 2 packs of 2');
        // And the loaded deal's shape is adopted by the form.
        await expect(page.locator('[data-form="generate"] input[name="packs"]')).toHaveValue('2');
    });

    test('an unreadable pack list is reported, not swallowed', async ({ page }) => {
        await page.route('**/magic/api/packs', (route) =>
            route.fulfill({
                status: 400,
                contentType: 'application/json',
                body: JSON.stringify({ ok: false, error: "That file doesn't have any card names in it." }),
            }));
        await page.goto('/magic.html#generate');

        await page.setInputFiles('[data-import-file="generate"]', {
            name: 'empty.txt',
            mimeType: 'text/plain',
            buffer: Buffer.from('# nothing here\n'),
        });

        await expect(page.locator('[data-status="generate"]')).toContainText("doesn't have any card names");
    });

    test('previewing a pasted list renders its cards by colour', async ({ page }) => {
        await stubApi(page, {
            '/magic/api/preview': {
                ok: true,
                poolSize: 2,
                groups: [{
                    category: 'Red',
                    count: 2,
                    asFan: 1,
                    cards: [card('Lightning Bolt'), card('Shock')],
                }],
                analytics: {
                    curve: [{ label: '1', count: 2 }],
                    averageManaValue: 1,
                    nonLandCount: 2,
                    types: [{ type: 'Instant', count: 2, asFan: 1 }],
                    rarities: [{ rarity: 'Common', count: 2, asFan: 1 }],
                    duplicates: [],
                    colorPairs: [],
                    colorPips: [],
                    totalValues: [],
                    valueExtremes: [],
                },
                notFound: [],
            },
        });
        await page.goto('/magic.html#preview');

        await page.click('[data-source-tab="list"]');
        await page.fill('textarea[name="list"]', 'Lightning Bolt\nShock');
        await page.click('[data-form="preview"] button[type="submit"]');

        await expect(page.locator('[data-result="preview"] .cube-card')).toHaveCount(2);
        await expect(page.locator('[data-result="preview"]')).toContainText('Lightning Bolt');
    });

    test('the as-fan calculator works with no network at all', async ({ page }) => {
        await stubApi(page, { '/magic/api/asfan': { ok: true, value: 1.6667 } });
        await page.goto('/magic.html#asfan');

        await page.fill('[data-form="asfan"] input[name="total"]', '60');
        await page.fill('[data-form="asfan"] input[name="cubeSize"]', '540');
        await page.click('[data-form="asfan"] button[type="submit"]');

        await expect(page.locator('[data-result="asfan"]')).toContainText('1.67');
    });
});

/** Drains a download stream into a string. */
function streamToString(stream) {
    return new Promise((resolve, reject) => {
        const chunks = [];
        stream.on('data', (c) => chunks.push(c));
        stream.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
        stream.on('error', reject);
    });
}
