package common.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class MediaTokenTest {

    private val id = "1_2_1"
    private val now: Instant = Instant.parse("2026-08-04T12:00:00Z")

    private fun tokenFor(id: String, at: Instant = now): Pair<Long, String> {
        val expiry = MediaToken.expiryFor(at)
        return expiry to MediaToken.sign(id, expiry)
    }

    @Test
    fun `a freshly minted token authorises its own id`() {
        val (expiry, signature) = tokenFor(id)

        assertTrue(MediaToken.isValid(id, expiry, signature, now))
    }

    @Test
    fun `an unsigned request is refused`() {
        // The whole point: `/music?id=…` on its own used to be enough, and the
        // id is two public Discord snowflakes.
        assertFalse(MediaToken.isValid(id, null, null, now))
        assertFalse(MediaToken.isValid(id, MediaToken.expiryFor(now), null, now))
        assertFalse(MediaToken.isValid(id, null, "deadbeef", now))
        assertFalse(MediaToken.isValid(id, MediaToken.expiryFor(now), "   ", now))
    }

    @Test
    fun `a token does not carry over to another intro`() {
        // Otherwise one legitimately obtained URL would unlock the whole table.
        val (expiry, signature) = tokenFor(id)

        assertFalse(MediaToken.isValid("1_2_2", expiry, signature, now))
        assertFalse(MediaToken.isValid("9_9_1", expiry, signature, now))
    }

    @Test
    fun `a token stops working once it expires`() {
        val (expiry, signature) = tokenFor(id)

        assertTrue(MediaToken.isValid(id, expiry, signature, now.plusSeconds(MediaToken.TTL_SECONDS)))
        assertFalse(MediaToken.isValid(id, expiry, signature, now.plusSeconds(MediaToken.TTL_SECONDS + 1)))
    }

    @Test
    fun `extending the deadline invalidates the signature`() {
        // The expiry is signed, so it can't be edited in the query string.
        val (expiry, signature) = tokenFor(id)

        assertFalse(MediaToken.isValid(id, expiry + 86_400, signature, now))
    }

    @Test
    fun `a forged signature is refused`() {
        val (expiry, signature) = tokenFor(id)
        val tampered = signature.replaceRange(0, 1, if (signature.startsWith("a")) "b" else "a")

        assertFalse(MediaToken.isValid(id, expiry, tampered, now))
        assertFalse(MediaToken.isValid(id, expiry, "", now))
        assertFalse(MediaToken.isValid(id, expiry, signature.dropLast(1), now))
    }

    @Test
    fun `the id and expiry cannot be shuffled between each other`() {
        // Without a separator, ("ab", 1) and ("a", 11) would hash identically
        // and a token for one intro would authorise another.
        assertFalse(MediaToken.sign("ab", 1) == MediaToken.sign("a", 11))
    }

    @Test
    fun `signing is deterministic within a process`() {
        // The player mints and the same process verifies; drifting per call
        // would reject every legitimate request.
        assertEquals(MediaToken.sign(id, 123), MediaToken.sign(id, 123))
    }

    @Test
    fun `the minted url carries the id and both token parameters`() {
        val url = MediaToken.urlFor(id, now)

        assertTrue(url.startsWith("/music?id=$id&"), url)
        assertTrue(url.contains("${MediaToken.EXPIRY_PARAM}="), url)
        assertTrue(url.contains("${MediaToken.SIGNATURE_PARAM}="), url)

        // And what it carries is accepted by the verifier.
        val expiry = Regex("${MediaToken.EXPIRY_PARAM}=(\\d+)").find(url)!!.groupValues[1].toLong()
        val signature = Regex("${MediaToken.SIGNATURE_PARAM}=([0-9a-f]+)").find(url)!!.groupValues[1]
        assertTrue(MediaToken.isValid(id, expiry, signature, now))
    }

    @Test
    fun `signatures are hex so they survive a query string unescaped`() {
        val (_, signature) = tokenFor(id)

        assertTrue(signature.matches(Regex("[0-9a-f]{64}")), signature)
    }
}
