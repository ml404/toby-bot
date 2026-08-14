// Playwright config for the responsive / mobile pass.
//
// These tests serve a small set of pre-built HTML fixtures from
// src/test/e2e/fixtures/ via Playwright's built-in static server. The
// fixtures are stripped-down renders of the real Thymeleaf templates
// (no Discord/DB lookups required) — enough HTML to assert layout
// behaviour at multiple viewports without spinning up Spring Boot.
//
// Two kinds of project:
//   * the four viewport projects below run responsive.spec.js only — the
//     same layout contract at each breakpoint;
//   * `magic` runs the behavioural spec once, on desktop. Driving the same
//     flows four times would cost four times as much and test the same JS.
//
// The viewport projects pin the four breakpoints that matter:
//   iPhone SE  (375×667) — small phone, our smallest "normal" target
//   Pixel 5    (393×851) — mid-phone, the most common Android width
//   iPad       (768×1024) — small tablet, the nav-collapse breakpoint
//   Desktop    (1280×800) — sanity baseline so phone fixes don't
//                            regress the wide layout
const { defineConfig, devices } = require('@playwright/test');
const path = require('path');

const FIXTURES_DIR = path.resolve(__dirname, 'src/test/e2e/fixtures');

// Environments that ship a pinned Chromium (sandboxes, locked-down CI images)
// can point at it rather than having Playwright download its own build:
//   PLAYWRIGHT_CHROMIUM_PATH=/opt/pw-browsers/chromium npx playwright test
// Unset — the normal case — Playwright resolves its own browser as usual.
const CHROMIUM_PATH = process.env.PLAYWRIGHT_CHROMIUM_PATH;
const launchOptions = CHROMIUM_PATH ? { executablePath: CHROMIUM_PATH } : {};

module.exports = defineConfig({
    testDir: path.resolve(__dirname, 'src/test/e2e'),
    timeout: 15_000,
    fullyParallel: true,
    forbidOnly: !!process.env.CI,
    reporter: process.env.CI ? 'github' : 'list',
    use: {
        baseURL: 'http://127.0.0.1:4173',
        ignoreHTTPSErrors: true,
        launchOptions,
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
            testMatch: /responsive\.spec\.js/,
            use: { ...devices['iPhone SE'] },
        },
        {
            name: 'pixel-5',
            testMatch: /responsive\.spec\.js/,
            use: { ...devices['Pixel 5'] },
        },
        {
            name: 'ipad',
            testMatch: /responsive\.spec\.js/,
            use: { ...devices['iPad (gen 7)'] },
        },
        {
            name: 'desktop',
            testMatch: /responsive\.spec\.js/,
            use: { viewport: { width: 1280, height: 800 } },
        },
        {
            name: 'magic',
            testMatch: /magic\.spec\.js/,
            use: { viewport: { width: 1280, height: 800 } },
        },
    ],
});
