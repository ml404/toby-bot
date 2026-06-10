// Discord Activity bootstrap. Runs inside the Activity iframe (served via
// the *.discordsays.com proxy) and performs the Embedded App SDK
// handshake:
//
//   ready → authorize (silent OAuth2 code grant) → POST the code to
//   /activity/api/token (mints an activity session token, see
//   ActivitySessionService) → authenticate → mount the casino.
//
// CRITICAL CONSTRAINT: Activities must behave as single-page apps. The
// SDK holds a persistent postMessage connection to the Discord client
// through THIS document — any top-level navigation (window.location,
// location.replace, a plain link) tears it down and the client kills
// the activity with "tried to open a disallowed web page". The casino
// is a multi-page Thymeleaf app, so it's mounted in a NESTED same-origin
// iframe below: navigation inside that frame is invisible to the
// sandbox, while this shell document (and the SDK connection) stays put.
//
// The session token rides into the nested frame as ?activityToken= and
// as the session cookie; api.js inside the frame attaches it as a bearer
// header on every fetch and onto same-origin link navigations.
//
// The SDK bundle is vendored (static/js/vendor/) rather than CDN-loaded:
// the Activity CSP only allows the app's own proxy origin, so a
// cdn.jsdelivr.net script tag would need its own URL mapping. Same-origin
// is simpler and survives portal misconfiguration.
import { DiscordSDK } from './vendor/discord-embedded-app-sdk-2.1.0.mjs';

const main = document.getElementById('activity-main');
const statusEl = document.getElementById('activity-status');

function status(message) {
    if (statusEl) statusEl.textContent = message;
}

function mountCasino(guildId, sessionToken) {
    const frame = document.createElement('iframe');
    frame.id = 'activity-game-frame';
    frame.title = 'TobyBot Casino';
    frame.src = '/activity/casino/' + encodeURIComponent(guildId)
        + '?activityToken=' + encodeURIComponent(sessionToken);
    frame.addEventListener('load', () => {
        if (main) main.hidden = true;
    });
    document.body.appendChild(frame);
}

async function boot() {
    const clientId = main && main.dataset ? main.dataset.clientId : '';
    if (!clientId) {
        status('Activity is not configured (missing Discord client id).');
        return;
    }

    const sdk = new DiscordSDK(clientId);

    status('Connecting to Discord…');
    await sdk.ready();

    if (!sdk.guildId) {
        status('No server context — launch the activity from a voice channel in a server.');
        return;
    }

    status('Authorising…');
    // prompt:'none' keeps relaunches silent once the user has consented.
    // Scopes mirror the web dashboard's OAuth2 login (identify,guilds).
    const { code } = await sdk.commands.authorize({
        client_id: clientId,
        response_type: 'code',
        state: '',
        prompt: 'none',
        scope: ['identify', 'guilds'],
    });

    status('Signing in…');
    const response = await fetch('/activity/api/token', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
        body: JSON.stringify({ code: code }),
    });
    const body = await response.json().catch(() => ({ ok: false }));
    if (!body.ok || !body.sessionToken || !body.accessToken) {
        throw new Error((body && body.error) || 'Sign-in failed — relaunch the activity.');
    }

    await sdk.commands.authenticate({ access_token: body.accessToken });

    try {
        sessionStorage.setItem('tobyActivityToken', body.sessionToken);
    } catch (e) {
        // Storage can be unavailable in a sandboxed iframe — the query
        // param on the nested frame still authenticates it, and api.js
        // falls back to reading the token from the frame's URL.
    }

    status('Entering the casino…');
    mountCasino(sdk.guildId, body.sessionToken);
}

boot().catch((e) => {
    status('Could not start the casino: ' + (e && e.message ? e.message : 'unknown error'));
});
