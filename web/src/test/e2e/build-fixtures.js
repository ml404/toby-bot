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
 * Output goes to ./fixtures/{html files} + ./fixtures/css/{copied}.
 * Re-run after editing CSS or fixture HTML.
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '../../..');
const CSS_SRC = path.join(ROOT, 'src/main/resources/static/css');
const FIXTURES = path.resolve(__dirname, 'fixtures');
const CSS_DEST = path.join(FIXTURES, 'css');

function copyCss() {
    fs.mkdirSync(CSS_DEST, { recursive: true });
    for (const f of fs.readdirSync(CSS_SRC)) {
        if (!f.endsWith('.css')) continue;
        fs.copyFileSync(path.join(CSS_SRC, f), path.join(CSS_DEST, f));
    }
}

copyCss();
console.log(`fixtures: copied CSS from ${CSS_SRC} to ${CSS_DEST}`);
