// Playwright config for the responsive / mobile pass.
//
// These tests serve a small set of pre-built HTML fixtures from
// src/test/e2e/fixtures/ via Playwright's built-in static server. The
// fixtures are stripped-down renders of the real Thymeleaf templates
// (no Discord/DB lookups required) — enough HTML to assert layout
// behaviour at multiple viewports without spinning up Spring Boot.
//
// Each test project pins a different viewport so a single spec run
// covers the four breakpoints that matter:
//   iPhone SE  (375×667) — small phone, our smallest "normal" target
//   Pixel 5    (393×851) — mid-phone, the most common Android width
//   iPad       (768×1024) — small tablet, the nav-collapse breakpoint
//   Desktop    (1280×800) — sanity baseline so phone fixes don't
//                            regress the wide layout
const { defineConfig, devices } = require('@playwright/test');
const path = require('path');

const FIXTURES_DIR = path.resolve(__dirname, 'src/test/e2e/fixtures');

module.exports = defineConfig({
    testDir: path.resolve(__dirname, 'src/test/e2e'),
    timeout: 15_000,
    fullyParallel: true,
    forbidOnly: !!process.env.CI,
    reporter: process.env.CI ? 'github' : 'list',
    use: {
        baseURL: 'http://127.0.0.1:4173',
        ignoreHTTPSErrors: true,
        // Snapshots live alongside the spec file in __snapshots__/.
        // Update with `npx playwright test --update-snapshots`.
    },
    webServer: {
        // `npx http-server` would do but adds a dependency — use Python's
        // built-in static server instead so the harness only needs Node +
        // a system Python (every Linux CI image has one).
        command: `python3 -m http.server 4173 --directory "${FIXTURES_DIR}"`,
        url: 'http://127.0.0.1:4173/',
        reuseExistingServer: !process.env.CI,
        timeout: 10_000,
    },
    projects: [
        {
            name: 'iphone-se',
            use: { ...devices['iPhone SE'] },
        },
        {
            name: 'pixel-5',
            use: { ...devices['Pixel 5'] },
        },
        {
            name: 'ipad',
            use: { ...devices['iPad (gen 7)'] },
        },
        {
            name: 'desktop',
            use: { viewport: { width: 1280, height: 800 } },
        },
    ],
});
