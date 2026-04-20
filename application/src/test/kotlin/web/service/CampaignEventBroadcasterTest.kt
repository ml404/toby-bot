package web.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import database.dto.CampaignEventDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class CampaignEventBroadcasterTest {

    private lateinit var broadcaster: CampaignEventBroadcaster
    private val objectMapper = ObjectMapper().registerModule(JavaTimeModule())

    private val campaignId = 10L

    private fun makeDto(id: Long = 1L, type: String = "ROLL"): CampaignEventDto =
        CampaignEventDto(
            id = id,
            campaignId = campaignId,
            eventType = type,
            actorDiscordId = 7L,
            actorName = "Dave",
            payload = """{"sides":20,"count":1,"modifier":0,"rawTotal":14,"total":14}""",
            createdAt = LocalDateTime.now()
        )

    @BeforeEach
    fun setUp() {
        broadcaster = CampaignEventBroadcaster(objectMapper)
    }

    @Test
    fun `subscribe registers emitter and increments count`() {
        assertEquals(0, broadcaster.subscriberCount(campaignId))

        val emitter = broadcaster.subscribe(campaignId)

        assertNotNull(emitter)
        assertEquals(1, broadcaster.subscriberCount(campaignId))
    }

    @Test
    fun `subscribe on different campaigns is isolated`() {
        broadcaster.subscribe(campaignId)
        broadcaster.subscribe(99L)
        broadcaster.subscribe(99L)

        assertEquals(1, broadcaster.subscriberCount(campaignId))
        assertEquals(2, broadcaster.subscriberCount(99L))
    }

    @Test
    fun `publish with no subscribers does not throw`() {
        broadcaster.publish(campaignId, makeDto())
        // no assertion — the point is it completes cleanly
        assertEquals(0, broadcaster.subscriberCount(campaignId))
    }

    @Test
    fun `publish tolerates unparseable payload`() {
        broadcaster.subscribe(campaignId)
        val bad = makeDto().apply { payload = "not-json" }

        // Should not throw even if the payload can't be deserialised to Map.
        broadcaster.publish(campaignId, bad)

        // Emitter still registered (send succeeded; payload fell back to empty map).
        assertEquals(1, broadcaster.subscriberCount(campaignId))
    }

    // SseEmitter's onCompletion / onTimeout callbacks are driven by the MVC
    // request lifecycle, not by emitter.complete() in a plain unit test — they
    // only fire after the async dispatch ends. Pruning behaviour is exercised
    // in PR B's broader manual test plan; a dedicated unit test here would
    // have to reach into Spring internals.
}
