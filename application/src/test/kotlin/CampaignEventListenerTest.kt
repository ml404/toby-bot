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
import web.service.CampaignEventBroadcaster

class CampaignEventListenerTest {

    private lateinit var campaignService: CampaignService
    private lateinit var campaignEventService: CampaignEventService
    private lateinit var broadcaster: CampaignEventBroadcaster
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
        broadcaster = mockk(relaxed = true)
        listener = CampaignEventListener(campaignService, campaignEventService, broadcaster)
    }

    @Test
    fun `persists event and broadcasts when active campaign exists`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        val captured = slot<CampaignEventDto>()
        every { campaignEventService.append(capture(captured)) } answers {
            captured.captured.apply { id = 77L }
        }

        val incoming = CampaignEventOccurred(
            guildId = guildId,
            type = CampaignEventType.ROLL,
            actorDiscordId = 42L,
            actorName = "Dave",
            payloadJson = """{"total":14}"""
        )
        listener.onCampaignEvent(incoming)

        val persisted = captured.captured
        assertEquals(campaign.id, persisted.campaignId)
        assertEquals(CampaignEventType.ROLL.name, persisted.eventType)
        verify {
            broadcaster.publish(campaign.id, match { it.id == 77L && it.eventType == "ROLL" })
        }
    }

    @Test
    fun `silently skips when no active campaign`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns null

        listener.onCampaignEvent(
            CampaignEventOccurred(guildId, CampaignEventType.ROLL, payloadJson = "{}")
        )

        verify(exactly = 0) { campaignEventService.append(any()) }
        verify(exactly = 0) { broadcaster.publish(any(), any()) }
    }

    @Test
    fun `skips broadcast when persistence fails`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { campaignEventService.append(any()) } throws RuntimeException("boom")

        listener.onCampaignEvent(
            CampaignEventOccurred(guildId, CampaignEventType.ROLL, payloadJson = "{}")
        )

        verify(exactly = 0) { broadcaster.publish(any(), any()) }
    }

    @Test
    fun `swallows broadcast failure`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { campaignEventService.append(any()) } answers {
            firstArg<CampaignEventDto>().apply { id = 1L }
        }
        every { broadcaster.publish(any(), any()) } throws RuntimeException("network")

        // Must not escape: publisher thread stays healthy.
        listener.onCampaignEvent(
            CampaignEventOccurred(guildId, CampaignEventType.ROLL, payloadJson = "{}")
        )
    }
}
