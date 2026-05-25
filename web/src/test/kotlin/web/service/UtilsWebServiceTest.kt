package web.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Covers the pre-HTTP validation branches of [UtilsWebService.randomMeme].
 * The post-HTTP parsing branches require an [java.net.http.HttpClient]
 * round-trip and are intentionally left out — the validation is the
 * security-sensitive piece (URL-injection scrub on `subreddit`).
 */
class UtilsWebServiceTest {

    private val service = UtilsWebService()

    @Test
    fun `blank subreddit fails fast without any HTTP attempt`() {
        val result = service.randomMeme(subreddit = "   ", timePeriod = "day", limit = 10)

        assertNull(result.value)
        assertEquals("Subreddit is required.", result.error)
    }

    @Test
    fun `subreddit with URL-injection chars is rejected (path-traversal scrub)`() {
        // Any non-alphanumeric/underscore char breaks the regex and the
        // request is dropped before reaching the Reddit URL sink.
        listOf(
            "memes/../admin",
            "memes?token=x",
            "memes&other=1",
            "memes;rm -rf",
            "memes evil",
        ).forEach { subreddit ->
            val result = service.randomMeme(subreddit, "day", 10)

            assertNull(result.value, "expected rejection for `$subreddit`")
            assertEquals("Invalid subreddit name.", result.error)
        }
    }

    @Test
    fun `subreddit longer than 50 chars is rejected`() {
        // Reddit's own cap is 21, but the regex caps at 50 — anything
        // beyond is treated as malformed input.
        val tooLong = "a".repeat(51)
        val result = service.randomMeme(tooLong, "day", 10)

        assertEquals("Invalid subreddit name.", result.error)
    }

    @Test
    fun `sneakybackgroundfeet is blocked case-insensitively before any HTTP attempt`() {
        // Source comment "Don't talk to me." — explicit blocklist for the
        // one subreddit known to ship NSFW content past Reddit's flags.
        listOf("sneakybackgroundfeet", "SNEAKYBACKGROUNDFEET", "SneakyBackgroundFeet").forEach { sub ->
            val result = service.randomMeme(sub, "day", 10)

            assertEquals("Don't talk to me.", result.error)
        }
    }

    @Test
    fun `valid subreddit name passes validation - error then comes from the actual HTTP attempt`() {
        // We don't stub HttpClient — Reddit is unreachable from the test
        // sandbox, so this exercises that validation gives way to the
        // network layer. The exact error string varies by environment;
        // what matters is that we didn't get a validation rejection.
        val result = service.randomMeme("memes", "day", 10)

        val validationRejects = setOf(
            "Subreddit is required.", "Invalid subreddit name.", "Don't talk to me.",
        )
        assertNotNull(result.error, "expected an error from the network layer, not a successful fetch")
        assert(result.error !in validationRejects) {
            "validation should not reject 'memes' — got: ${result.error}"
        }
    }

    @Test
    fun `UtilsResult ok and error helpers produce mutually-exclusive shapes`() {
        val ok = UtilsResult.ok("hi")
        assertEquals("hi", ok.value)
        assertNull(ok.error)

        val err = UtilsResult.error<String>("nope")
        assertNull(err.value)
        assertEquals("nope", err.error)
    }
}
