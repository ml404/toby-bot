import common.events.CampaignEventOccurred
import common.events.CampaignEventType
import database.dto.CampaignDto
import database.dto.CampaignEventDto
import database.service.CampaignEventService
import database.service.CampaignService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CampaignEventListenerTest {

    private lateinit var campaignService: CampaignService
    private lateinit var campaignEventService: CampaignEventService
    private lateinit var listener: CampaignEventListener

    private val guildId = 100L

    private fun makeCampaign(id: Long = 10L) = CampaignDto(
        id = id,
        guildId = guildId,
        channelId = guildId,
        dmDiscordId = 1L,
        name = "Test Campaign"
    )

    @BeforeEach
    fun setUp() {
        campaignService = mockk()
        campaignEventService = mockk(relaxed = true)
        listener = CampaignEventListener(campaignService, campaignEventService)
    }

    @Test
    fun `persists event when active campaign exists`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        val captured = slot<CampaignEventDto>()
        every { campaignEventService.append(capture(captured)) } answers { captured.captured }

        val incoming = CampaignEventOccurred(
            guildId = guildId,
            type = CampaignEventType.ROLL,
            actorDiscordId = 42L,
            actorName = "Dave",
            payloadJson = """{"sides":20,"count":1,"modifier":0,"total":14,"rawTotal":14}"""
        )
        listener.onCampaignEvent(incoming)

        val persisted = captured.captured
        assertEquals(campaign.id, persisted.campaignId)
        assertEquals(CampaignEventType.ROLL.name, persisted.eventType)
        assertEquals(42L, persisted.actorDiscordId)
        assertEquals("Dave", persisted.actorName)
        assertEquals(incoming.payloadJson, persisted.payload)
    }

    @Test
    fun `silently skips when no active campaign`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns null

        listener.onCampaignEvent(
            CampaignEventOccurred(guildId, CampaignEventType.ROLL, payloadJson = "{}")
        )

        verify(exactly = 0) { campaignEventService.append(any()) }
    }

    @Test
    fun `swallows persistence failure`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { campaignEventService.append(any()) } throws RuntimeException("boom")

        listener.onCampaignEvent(
            CampaignEventOccurred(guildId, CampaignEventType.ROLL, payloadJson = "{}")
        )
        // No exception propagated — listener must not crash the publisher thread.
    }
}
