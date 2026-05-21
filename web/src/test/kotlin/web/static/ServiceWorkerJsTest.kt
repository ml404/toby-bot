package web.static

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Regression net for `static/sw.js`. The service worker is small but
 * carries an invariant that's not obvious from the call site: it must
 * skip `showNotification` when a same-origin tab is visible, otherwise
 * push fires alongside the SSE-driven in-page toast and the user sees
 * the same alert twice on the device they're currently using.
 *
 * Dropping the visibility check would be a silent UX regression — no
 * runtime test catches it because both surfaces are "working as
 * designed" individually. Locking it in here keeps the foreground-
 * suppression contract visible to anyone editing the SW.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServiceWorkerJsTest {

    private lateinit var script: String

    @BeforeAll
    fun loadScript() {
        script = javaClass.classLoader
            .getResourceAsStream("static/sw.js")
            ?.bufferedReader()
            ?.readText()
            ?: error("static/sw.js is not on the classpath")
    }

    @Test
    fun `push handler is registered`() {
        assertTrue(
            script.contains("addEventListener('push'") ||
                script.contains("addEventListener(\"push\""),
            "SW must register a push listener — without it no notifications fire at all.",
        )
    }

    @Test
    fun `push handler queries clients to detect a visible tab`() {
        assertTrue(
            script.contains("clients.matchAll"),
            "Foreground suppression depends on enumerating window clients.",
        )
        assertTrue(
            script.contains("visibilityState"),
            "SW must check visibilityState to suppress when a tab is visible.",
        )
    }

    @Test
    fun `push handler suppresses showNotification when a tab is visible`() {
        // The visibility check must short-circuit BEFORE the actual
        // showNotification call (not before the word in a comment) —
        // otherwise we'd still pop the OS banner. Match on the call
        // form (`registration.showNotification`) so the comment above
        // can mention `showNotification` without confusing the matcher.
        val visibilityIdx = script.indexOf("visibilityState")
        val showIdx = script.indexOf("registration.showNotification")
        assertTrue(
            visibilityIdx in 0..<showIdx,
            "Visibility check must precede registration.showNotification — otherwise foreground suppression doesn't take effect.",
        )
    }

    @Test
    fun `push handler still calls showNotification for the background path`() {
        // Foreground suppression must NOT remove the OS-notification path
        // entirely — when no tab is visible (the user is away from the
        // site) the push is the only way to reach them. Match on the
        // call form, not just the bare word (which also appears in
        // comments).
        assertTrue(
            script.contains("registration.showNotification"),
            "SW must still call registration.showNotification on the background path.",
        )
    }

    @Test
    fun `notificationclick handler is preserved`() {
        // Pre-existing behaviour — focus an existing tab or open a new
        // one. Not part of this PR but easy to lock in to catch
        // accidental regression.
        assertTrue(
            script.contains("notificationclick"),
            "SW must still handle notificationclick to deep-link into the site.",
        )
    }
}
