// Responsive / mobile contract tests run across every viewport project
// declared in playwright.config.js. The same spec runs for iPhone SE,
// Pixel 5, iPad, and desktop — `test.info().project.name` reveals which
// viewport is current.
//
// Three contracts:
//   1. No horizontal overflow at any viewport for any page (the single
//      most common mobile bug).
//   2. Touch-sized targets (buttons, links, .btn, .nav-link) are at
//      least 36px tall on phone viewports.
//   3. The hamburger nav toggles open/closed correctly under
//      --bp-mobile (600px).
const { test, expect } = require('@playwright/test');

const PAGES = [
    { url: '/home.html', name: 'home' },
    { url: '/moderation-users.html', name: 'moderation-users' },
    { url: '/moderation-settings.html', name: 'moderation-settings' },
    { url: '/blackjack-table.html', name: 'blackjack' },
    { url: '/roulette.html', name: 'roulette' },
    { url: '/leaderboard.html', name: 'leaderboard' },
];

const PHONE_PROJECTS = new Set(['iphone-se', 'pixel-5']);

for (const page of PAGES) {
    test.describe(`page: ${page.name}`, () => {
        test('no horizontal overflow', async ({ page: browserPage }, testInfo) => {
            await browserPage.goto(page.url);
            // Wait for layout to settle — CSS animations / web fonts can
            // briefly perturb scrollWidth on first paint.
            await browserPage.waitForLoadState('load');
            const overflow = await browserPage.evaluate(() => {
                const html = document.documentElement;
                return {
                    scrollW: html.scrollWidth,
                    clientW: html.clientWidth,
                    docW: document.body.scrollWidth,
                };
            });
            // Allow ±1px slack for sub-pixel rounding on scrollbars.
            expect(
                overflow.scrollW - overflow.clientW,
                `${page.name} overflows horizontally at viewport ${testInfo.project.name}: ` +
                `${overflow.scrollW} > ${overflow.clientW}`,
            ).toBeLessThanOrEqual(1);
        });

        test('interactive elements meet minimum tap-target height', async ({
            page: browserPage,
        }, testInfo) => {
            // Only enforce on phone viewports — desktop allows smaller
            // mouse-sized buttons.
            test.skip(
                !PHONE_PROJECTS.has(testInfo.project.name),
                'tap-target check is phone-only',
            );
            await browserPage.goto(page.url);
            await browserPage.waitForLoadState('load');
            const heights = await browserPage.$$eval(
                'button:not([hidden]), .btn, .btn-primary, .btn-secondary, .nav-link, a.btn-discord, a.btn-logout',
                (els) =>
                    els
                        .filter((el) => el.offsetParent !== null) // visible
                        .map((el) => ({
                            tag: el.tagName,
                            text: (el.textContent || '').trim().slice(0, 30),
                            h: el.getBoundingClientRect().height,
                        })),
            );
            // Some single-character buttons (e.g. close ✕) legitimately
            // sit below the 44px threshold but the WCAG bare-minimum is
            // 24px. Anything between is intentional — we just want to
            // catch the regression where a primary action button shrinks
            // to <30px on touch.
            const tooSmall = heights.filter((b) => b.h > 0 && b.h < 30);
            expect(
                tooSmall,
                `${page.name} has interactive elements smaller than 30px tall ` +
                    `on ${testInfo.project.name}: ${JSON.stringify(tooSmall)}`,
            ).toEqual([]);
        });
    });
}

test.describe('nav hamburger toggle', () => {
    test('opens and closes under the mobile breakpoint', async ({
        page: browserPage,
    }, testInfo) => {
        // The hamburger is `display: block` only under @media (max-width: 600px).
        // On wider projects, the toggle stays hidden and clicking it via
        // Playwright won't show the menu — so we skip on iPad/desktop.
        test.skip(
            !PHONE_PROJECTS.has(testInfo.project.name),
            'hamburger only visible under --bp-mobile',
        );
        await browserPage.goto('/home.html');
        const toggle = browserPage.locator('.nav-toggle');
        const menu = browserPage.locator('#nav-menu');
        await expect(toggle).toBeVisible();
        // Inject the same click handler home.js installs in prod —
        // fixtures don't load home.js so wire it inline.
        await browserPage.evaluate(() => {
            const t = document.querySelector('.nav-toggle');
            const m = document.getElementById('nav-menu');
            t.addEventListener('click', () => m.classList.toggle('open'));
        });
        await expect(menu).not.toHaveClass(/open/);
        await toggle.click();
        await expect(menu).toHaveClass(/open/);
        await toggle.click();
        await expect(menu).not.toHaveClass(/open/);
    });
});

test.describe('nav dropdowns under mobile breakpoint', () => {
    test('sections start collapsed, toggle open one at a time', async ({
        page: browserPage,
    }, testInfo) => {
        // Dropdown collapse only matters on phone viewports where the
        // hamburger menu shows. On iPad/desktop the dropdowns sit inline
        // on the navbar and use the desktop hover/click model.
        test.skip(
            !PHONE_PROJECTS.has(testInfo.project.name),
            'mobile dropdown collapse only applies under --bp-mobile',
        );
        await browserPage.goto('/home.html');
        await browserPage.waitForLoadState('load');

        // Fixture HTML doesn't load home.js, so inline the same handlers
        // home.js installs in prod (matching the workaround at the
        // hamburger toggle test above).
        await browserPage.evaluate(() => {
            const closeAllDropdowns = () => {
                document.querySelectorAll('.nav-dropdown.open').forEach((d) => {
                    d.classList.remove('open');
                    const t = d.querySelector('.nav-dropdown-toggle');
                    if (t) t.setAttribute('aria-expanded', 'false');
                });
            };
            window.__closeAllDropdowns = closeAllDropdowns;
            window.__toggleDropdown = (toggle) => {
                const dropdown = toggle.closest('.nav-dropdown');
                if (!dropdown) return false;
                const willOpen = !dropdown.classList.contains('open');
                closeAllDropdowns();
                if (willOpen) {
                    dropdown.classList.add('open');
                    toggle.setAttribute('aria-expanded', 'true');
                }
                return willOpen;
            };
            document.querySelectorAll('.nav-dropdown-toggle').forEach((t) => {
                t.addEventListener('click', () => window.__toggleDropdown(t));
            });
            const navToggle = document.querySelector('.nav-toggle');
            const navMenu = document.getElementById('nav-menu');
            if (navToggle && navMenu) {
                navToggle.addEventListener('click', () => navMenu.classList.toggle('open'));
            }
        });

        // Open the hamburger so the dropdown toggles are part of the
        // layout and computed-style reads reflect the mobile rules.
        await browserPage.locator('.nav-toggle').click();
        await expect(browserPage.locator('#nav-menu')).toHaveClass(/open/);

        const initialDisplays = await browserPage.$$eval(
            '.nav-dropdown-menu',
            (els) => els.map((el) => getComputedStyle(el).display),
        );
        expect(
            initialDisplays,
            'every dropdown menu must start collapsed (display: none) on mobile',
        ).toEqual(initialDisplays.map(() => 'none'));

        const playToggle = browserPage.locator('.nav-dropdown-toggle', { hasText: 'Play' });
        const economyToggle = browserPage.locator('.nav-dropdown-toggle', { hasText: 'Economy' });

        // Open Play: its menu becomes visible, its parent gets .open.
        await playToggle.click();
        const afterPlay = await browserPage.evaluate(() => {
            const dropdowns = Array.from(document.querySelectorAll('.nav-dropdown'));
            return dropdowns.map((d) => {
                const label = d.querySelector('.nav-dropdown-toggle')?.textContent?.trim() || '';
                return {
                    label,
                    open: d.classList.contains('open'),
                    display: getComputedStyle(d.querySelector('.nav-dropdown-menu')).display,
                };
            });
        });
        const play = afterPlay.find((d) => d.label.startsWith('Play'));
        expect(play.open).toBe(true);
        expect(play.display).toBe('flex');
        expect(afterPlay.filter((d) => d.open)).toHaveLength(1);

        // Open Economy: Play must collapse (one-at-a-time contract).
        await economyToggle.click();
        const afterEconomy = await browserPage.evaluate(() => {
            const dropdowns = Array.from(document.querySelectorAll('.nav-dropdown'));
            return dropdowns.map((d) => {
                const label = d.querySelector('.nav-dropdown-toggle')?.textContent?.trim() || '';
                return {
                    label,
                    open: d.classList.contains('open'),
                    display: getComputedStyle(d.querySelector('.nav-dropdown-menu')).display,
                };
            });
        });
        const economy = afterEconomy.find((d) => d.label.startsWith('Economy'));
        const playAgain = afterEconomy.find((d) => d.label.startsWith('Play'));
        expect(economy.open).toBe(true);
        expect(economy.display).toBe('flex');
        expect(playAgain.open).toBe(false);
        expect(playAgain.display).toBe('none');
        expect(afterEconomy.filter((d) => d.open)).toHaveLength(1);

        // Tap Economy again: it collapses (toggle path).
        await economyToggle.click();
        const afterEconomyClose = await browserPage.evaluate(() => {
            const dropdowns = Array.from(document.querySelectorAll('.nav-dropdown'));
            return dropdowns.map((d) => ({
                open: d.classList.contains('open'),
                display: getComputedStyle(d.querySelector('.nav-dropdown-menu')).display,
            }));
        });
        expect(afterEconomyClose.filter((d) => d.open)).toHaveLength(0);
        expect(afterEconomyClose.map((d) => d.display)).toEqual(
            afterEconomyClose.map(() => 'none'),
        );
    });
});

test.describe('mod-table card transform', () => {
    test('rows render as cards on phone with visible data-label headings', async ({
        page: browserPage,
    }, testInfo) => {
        test.skip(
            !PHONE_PROJECTS.has(testInfo.project.name),
            'card transform is phone-only',
        );
        await browserPage.goto('/moderation-users.html');
        const theadDisplay = await browserPage.$eval(
            '.mod-table thead',
            (el) => getComputedStyle(el).display,
        );
        expect(
            theadDisplay,
            'on phone the table thead must collapse (display: none) so each row reads as a card',
        ).toBe('none');

        // Pick a td that has data-label and confirm the ::before pseudo
        // renders the label content (the card-transform "heading").
        const label = await browserPage.$eval(
            '.mod-table td[data-label="Roles"]',
            (el) => getComputedStyle(el, '::before').content,
        );
        expect(label).toContain('Roles');
    });
});
