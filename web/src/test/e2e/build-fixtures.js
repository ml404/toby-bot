#!/usr/bin/env node
/*
 * Builds the static fixtures Playwright serves. Three reasons we don't
 * point Playwright at the real Spring Boot dev server:
 *
 *   1. The Discord OAuth + DB dependency would force every CI run to
 *      stand up Postgres + a test bot token. The responsive tests don't
 *      exercise auth/data flow — they exercise CSS at different
 *      viewports.
 *   2. The fixtures are deterministic. The real templates render
 *      different rows based on the DB state, so visual snapshots would
 *      churn on every seed change.
 *   3. The fixture build copies the real CSS files unmodified so the
 *      Playwright snapshot pins the actual production stylesheet.
 *
 * Output goes to ./fixtures/{html files} + ./fixtures/css/{copied} +
 * ./fixtures/js/{copied}. Re-run after editing CSS/JS or fixture HTML.
 *
 * The JS is copied for the /magic spec, which drives the real page scripts
 * rather than asserting on markup: unlike the responsive fixtures, that one
 * needs the page to actually work. Its HTML (fixtures/magic.html) is the real
 * template's render, refreshed via MagicE2eFixtureTest.
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '../../..');
const CSS_SRC = path.join(ROOT, 'src/main/resources/static/css');
const JS_SRC = path.join(ROOT, 'src/main/resources/static/js');
const FIXTURES = path.resolve(__dirname, 'fixtures');
const CSS_DEST = path.join(FIXTURES, 'css');
const JS_DEST = path.join(FIXTURES, 'js');

/** Copies every file with [ext] from [src] to [dest], flat. */
function copyAssets(src, dest, ext) {
    fs.mkdirSync(dest, { recursive: true });
    let copied = 0;
    for (const f of fs.readdirSync(src)) {
        if (!f.endsWith(ext)) continue;
        fs.copyFileSync(path.join(src, f), path.join(dest, f));
        copied++;
    }
    return copied;
}

const cssCount = copyAssets(CSS_SRC, CSS_DEST, '.css');
console.log(`fixtures: copied ${cssCount} CSS files from ${CSS_SRC} to ${CSS_DEST}`);

const jsCount = copyAssets(JS_SRC, JS_DEST, '.js');
console.log(`fixtures: copied ${jsCount} JS files from ${JS_SRC} to ${JS_DEST}`);

if (!fs.existsSync(path.join(FIXTURES, 'magic.html'))) {
    console.warn(
        'fixtures: magic.html is missing — regenerate it with\n' +
        "  ./gradlew :web:test --tests '*MagicE2eFixtureTest*' -Dfixture.refresh=true",
    );
}
