package web.static

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Regression net for `static/js/notifications-stream.js`. The script
 * itself is small but bridges several invariants we rely on:
 *   - it bails on anonymous pages (no `<meta name="user-authenticated">`),
 *     so dropping the guard would spam 401s from every logged-out hit
 *   - it listens for every event name the backend `NotificationSseService`
 *     fires — adding a kind on the backend without adding the listener
 *     here would silently swallow the toast
 *   - it reconnects with bounded backoff so a flapping network doesn't
 *     hot-loop on the server
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationsStreamJsTest {

    private lateinit var script: String

    @BeforeAll
    fun loadScript() {
        script = javaClass.classLoader
            .getResourceAsStream("static/js/notifications-stream.js")
            ?.bufferedReader()
            ?.readText()
            ?: error("static/js/notifications-stream.js is not on the classpath")
    }

    @Test
    fun `script bails when the user-authenticated meta tag is absent`() {
        assertTrue(
            script.contains("meta[name=\"user-authenticated\"]"),
            "Script must guard on the meta tag so anonymous pages don't open a doomed SSE.",
        )
        // The guard must precede the actual EventSource construction —
        // searching for `new EventSource(` rather than bare `EventSource`
        // skips the leading-comment reference to the API name.
        val metaIdx = script.indexOf("meta[name=\"user-authenticated\"]")
        val constructIdx = script.indexOf("new EventSource(")
        assertTrue(metaIdx in 0..<constructIdx, "auth guard must precede EventSource construction")
    }

    @Test
    fun `script opens the canonical notifications stream endpoint`() {
        assertTrue(
            script.contains("/api/notifications/stream"),
            "Stream URL must match the controller path so a rename here or there is caught.",
        )
        assertTrue(
            script.contains("new EventSource("),
            "Stream must use the EventSource API, not fetch/XHR polling.",
        )
    }

    @Test
    fun `script registers a listener for every event the backend fires`() {
        // These names must round-trip with NotificationSseService.
        // Drift on either side silently swallows the toast.
        listOf("achievement", "levelUp", "tip", "lotteryDrawn").forEach { name ->
            assertTrue(
                script.contains("'$name'") || script.contains("\"$name\""),
                "Script must register a listener for the `$name` event.",
            )
        }
    }

    @Test
    fun `script wires reconnection with bounded backoff`() {
        assertTrue(
            script.contains("setTimeout"),
            "Reconnect must defer via setTimeout (not a hot loop).",
        )
        assertTrue(
            script.contains("backoff"),
            "Reconnect must use a backoff variable (named for diagnostic logging).",
        )
        // A hot-retry without a cap would set `backoff` from a constant
        // every retry. The bounded version doubles up to a max.
        assertTrue(
            script.contains("MAX_BACKOFF_MS"),
            "Reconnect must cap its delay so flapping networks don't trip a heavy retry storm.",
        )
    }

    @Test
    fun `script uses TobyToasts to render the toast`() {
        assertTrue(
            script.contains("window.TobyToasts"),
            "Script must call into the shared toast UI rather than building its own.",
        )
        assertTrue(
            script.contains(".show("),
            "Script must call TobyToasts.show (the rich-options entry point) to pass title/body/deepLink.",
        )
    }

    @Test
    fun `script does not synchronously hit the network on parse`() {
        // Connect runs inside an IIFE that gates on the auth meta;
        // outside the IIFE there is nothing that would fetch on parse.
        assertFalse(
            script.contains("fetch("),
            "Script must not eagerly fetch on parse — the EventSource is the only network call.",
        )
    }

    @Test
    fun `script does not leak globals beyond the IIFE`() {
        // The whole script is one IIFE; no top-level `window.X = …`
        // assignments are expected.
        assertFalse(
            script.contains("window.notificationsStream"),
            "Script should not export internal state to window.",
        )
    }
}
