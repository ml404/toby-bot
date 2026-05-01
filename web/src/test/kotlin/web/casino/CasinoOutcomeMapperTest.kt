package web.casino

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Locks down the canonical user-visible strings for casino-game failure
 * outcomes and the auth/membership guard. Any of the per-game controller
 * tests would catch a single drift, but this is the one place where
 * those strings live now — guard them here instead of relying on five
 * controller tests to all assert on the same substring.
 */
class CasinoOutcomeMapperTest {

    private data class FakeResponse(
        override val ok: Boolean,
        override val error: String? = null,
    ) : CasinoResponseLike

    private val mapper = CasinoOutcomeMapper { msg -> FakeResponse(false, msg) }

    @Test
    fun `errorBuilder maps 401 to the not-signed-in message`() {
        val response = mapper.errorBuilder(401)

        assertEquals(401, response.statusCode.value())
        assertEquals(false, response.body!!.ok)
        assertEquals("Not signed in.", response.body!!.error)
    }

    @Test
    fun `errorBuilder maps 403 to the not-a-member message`() {
        val response = mapper.errorBuilder(403)

        assertEquals(403, response.statusCode.value())
        assertEquals(false, response.body!!.ok)
        assertEquals("You are not a member of that server.", response.body!!.error)
    }

    @Test
    fun `insufficientCredits surfaces the requested stake and current balance`() {
        val response = mapper.insufficientCredits(stake = 100L, have = 30L)

        assertEquals(400, response.statusCode.value())
        assertEquals("Need 100 credits, you have 30.", response.body!!.error)
    }

    @Test
    fun `insufficientCoinsForTopUp surfaces needed and held coin amounts`() {
        val response = mapper.insufficientCoinsForTopUp(needed = 5L, have = 1L)

        assertEquals(400, response.statusCode.value())
        assertEquals("Need 5 TOBY to cover the shortfall, you have 1.", response.body!!.error)
    }

    @Test
    fun `invalidStake surfaces both bounds in the message`() {
        val response = mapper.invalidStake(min = 10L, max = 500L)

        assertEquals(400, response.statusCode.value())
        assertEquals("Stake must be between 10 and 500 credits.", response.body!!.error)
    }

    @Test
    fun `unknownUser nudges the user toward another bot command first`() {
        val response = mapper.unknownUser()

        assertEquals(400, response.statusCode.value())
        assertEquals("No user record yet. Try another TobyBot command first.", response.body!!.error)
    }

    @Test
    fun `badRequest passes through an arbitrary message verbatim`() {
        val response = mapper.badRequest("Pick a side: HEADS or TAILS.")

        assertEquals(400, response.statusCode.value())
        assertEquals("Pick a side: HEADS or TAILS.", response.body!!.error)
    }
}
