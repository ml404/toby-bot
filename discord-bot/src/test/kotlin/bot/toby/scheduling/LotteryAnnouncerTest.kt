package bot.toby.scheduling

import bot.toby.notify.NotificationRouter
import common.notification.PushAdapter
import common.notification.ChannelRouteKey
import common.notification.PushPayload
import database.dto.ConfigDto
import database.dto.JackpotLotteryDto
import database.service.ConfigService
import database.service.JackpotLotteryService
import database.service.UserNotificationPrefService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.SelfMember
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.requests.ErrorResponse
import net.dv8tion.jda.api.requests.RestAction
import net.dv8tion.jda.api.requests.restaction.MessageEditAction
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * After the routing refactor, `announceCycle` delegates channel
 * resolution + dispatch to `NotificationRouter.sendChannel`. The
 * announcer's job shrinks to:
 *   - building the embed
 *   - assembling the content (wide ping prefix + CTA + winner pings)
 *   - setting allowed-mention types so the wide ping isn't stripped
 *   - assembling the action-row component
 *   - capturing the sent message via the router's onSent callback so
 *     [JackpotLotteryService.recordAnnouncement] persists the message
 *     id for [LotteryAnnouncer.refreshAnnouncement] to edit later.
 *
 * Channel resolution itself (LOTTERY → LEADERBOARD → system) is now
 * covered by `NotificationRouterChannelTest`.
 */
class LotteryAnnouncerTest {

    private val guildId = 100L
    private lateinit var configService: ConfigService
    private lateinit var jackpotLotteryService: JackpotLotteryService
    private lateinit var router: NotificationRouter
    private lateinit var guild: Guild
    private lateinit var jda: JDA
    private lateinit var bot: SelfMember
    private lateinit var channel: TextChannel
    private lateinit var announcer: LotteryAnnouncer

    @BeforeEach
    fun setup() {
        configService = mockk(relaxed = true)
        jackpotLotteryService = mockk(relaxed = true)
        // Spy on a real router so dispatch's enforcement runs (the
        // LOTTERY cycle wires CHANNEL + PUSH, both required by the kind).
        val prefService = mockk<UserNotificationPrefService>(relaxed = true) {
            every { isOptedIn(any(), any(), any(), any()) } returns true
        }
        val routerConfigService = mockk<ConfigService>(relaxed = true)
        val pushAdapter = mockk<PushAdapter>(relaxed = true)
        jda = mockk(relaxed = true)
        router = spyk(NotificationRouter(jda, prefService, routerConfigService, pushAdapter))
        every { router.sendDm(any(), any(), any(), any()) } just runs
        every { router.sendPush(any(), any(), any(), any()) } just runs
        every {
            router.sendChannel(any(), any(), any(), any(), any(), any())
        } just runs
        guild = mockk(relaxed = true)
        bot = mockk(relaxed = true)
        channel = mockk(relaxed = true)

        every { guild.idLong } returns guildId
        every { guild.id } returns guildId.toString()
        every { guild.name } returns "Test Guild"
        every { guild.selfMember } returns bot
        every { guild.jda } returns jda
        every { channel.idLong } returns 777L
        // Slash-command id resolution: stub to empty → announcer falls
        // back to plain `/lottery buy` text. mockk's default-interface-method
        // handling can vary; an explicit stub is the safest.
        val restAction: RestAction<List<Command>> = mockk(relaxed = true)
        every { jda.retrieveCommands() } returns restAction
        every { restAction.complete() } returns emptyList()

        announcer = LotteryAnnouncer(configService, jackpotLotteryService, router)
    }

    private fun stubConfig(key: ConfigDto.Configurations, value: String?) {
        every {
            configService.getConfigByName(key.configValue, guildId.toString())
        } returns value?.let {
            ConfigDto(name = key.configValue, value = it, guildId = guildId.toString())
        }
    }

    /** Capture the lazy message-builder the announcer passes to the router. */
    private fun captureMessageBuilder(): () -> MessageCreateData {
        val builder = slot<() -> MessageCreateData>()
        every {
            router.sendChannel(
                guildId = any(),
                route = any(),
                originChannelId = any(),
                message = capture(builder),
                onSent = any(),
                mentions = any(),
            )
        } just runs
        return { builder.captured.invoke() }
    }

    /**
     * Capture the mentions arg the announcer passes to the router.
     * The announcer always passes a non-null ChannelMentions (with an
     * empty userIds list for no-winners cycles), so a non-nullable
     * slot keeps mockk's `capture(...)` overload resolution happy.
     */
    private fun captureMentions(): io.mockk.CapturingSlot<bot.toby.notify.ChannelMentions> {
        val mentions = slot<bot.toby.notify.ChannelMentions>()
        every {
            router.sendChannel(
                guildId = any(),
                route = any(),
                originChannelId = any(),
                message = any(),
                onSent = any(),
                mentions = capture(mentions),
            )
        } just runs
        return mentions
    }

    // ---- routing ----

    @Test
    fun `announceCycle routes through the LOTTERY ChannelRouteKey`() {
        captureMessageBuilder()

        announcer.announceCycle(
            guild, mode = "WEIGHTED",
            priorOutcome = null,
            openOutcome = LotteryAnnouncer.OpenSummary.Skipped,
        )

        verify(exactly = 1) {
            router.sendChannel(
                guildId = guildId,
                route = ChannelRouteKey.LOTTERY,
                originChannelId = null,
                message = any(),
                onSent = any(),
                mentions = any(),
            )
        }
    }

    @Test
    fun `announceCycle passes LOTTERY_DRAW_WITH_MY_TICKET mentions with winner ids`() {
        val mentions = captureMentions()
        val payouts = listOf(
            database.service.JackpotLotteryService.WinnerPayout(discordId = 1L, ticketCount = 5, amount = 500L),
            database.service.JackpotLotteryService.WinnerPayout(discordId = 2L, ticketCount = 3, amount = 300L),
        )
        announcer.announceCycle(
            guild, mode = "WEIGHTED",
            priorOutcome = LotteryAnnouncer.PriorOutcome.WeightedDrawn(
                payouts = payouts, totalPaid = 800L, drained = 1_000L,
            ),
            openOutcome = LotteryAnnouncer.OpenSummary.Ok(
                seeded = 500L, ticketPrice = 50L, poolAmount = 500L,
            ),
        )
        val m = mentions.captured
        assertEquals(
            common.notification.NotificationChannelKind.LOTTERY_DRAW_WITH_MY_TICKET, m.kind
        )
        assertEquals(listOf(1L, 2L), m.userIds)
    }

    @Test
    fun `WeightedDrawn cycle pushes each winner with their own payout amount in the body`() {
        // Regression guard: LOTTERY_DRAW_WITH_MY_TICKET supports
        // CHANNEL + PUSH, but historically only the channel post fired.
        // Multi-recipient dispatch now also fans push out per-winner.
        val payouts = listOf(
            database.service.JackpotLotteryService.WinnerPayout(discordId = 1L, ticketCount = 5, amount = 500L),
            database.service.JackpotLotteryService.WinnerPayout(discordId = 2L, ticketCount = 3, amount = 300L),
        )
        val pushBuilders = mutableMapOf<Long, () -> PushPayload>()
        every {
            router.sendPush(any(), any(), any(), any())
        } answers {
            @Suppress("UNCHECKED_CAST")
            pushBuilders[firstArg()] = arg<() -> PushPayload>(3)
        }

        announcer.announceCycle(
            guild, mode = "WEIGHTED",
            priorOutcome = LotteryAnnouncer.PriorOutcome.WeightedDrawn(
                payouts = payouts, totalPaid = 800L, drained = 1_000L,
            ),
            openOutcome = LotteryAnnouncer.OpenSummary.Ok(
                seeded = 500L, ticketPrice = 50L, poolAmount = 500L,
            ),
        )

        // Each winner got exactly one push targeted at their own id.
        verify(exactly = 1) {
            router.sendPush(1L, guildId, common.notification.NotificationChannelKind.LOTTERY_DRAW_WITH_MY_TICKET, any())
        }
        verify(exactly = 1) {
            router.sendPush(2L, guildId, common.notification.NotificationChannelKind.LOTTERY_DRAW_WITH_MY_TICKET, any())
        }
        // Payload bodies carry each winner's own payout amount.
        assert(pushBuilders.getValue(1L).invoke().body.contains("500"))
        assert(pushBuilders.getValue(2L).invoke().body.contains("300"))
    }

    @Test
    fun `MatchDrawn cycle pushes each tier winner with their tier share`() {
        val tierPayouts = listOf(
            database.service.JackpotLotteryService.MatchTierPayout(discordId = 7L, matches = 5, share = 600L),
            database.service.JackpotLotteryService.MatchTierPayout(discordId = 8L, matches = 4, share = 250L),
            database.service.JackpotLotteryService.MatchTierPayout(discordId = 9L, matches = 3, share = 100L),
        )
        val pushBuilders = mutableMapOf<Long, () -> PushPayload>()
        every {
            router.sendPush(any(), any(), any(), any())
        } answers {
            @Suppress("UNCHECKED_CAST")
            pushBuilders[firstArg()] = arg<() -> PushPayload>(3)
        }

        announcer.announceCycle(
            guild, mode = "WEIGHTED", // mode label only — outcome is MatchDrawn
            priorOutcome = LotteryAnnouncer.PriorOutcome.MatchDrawn(
                drawnNumbers = listOf(1, 2, 3, 4, 5),
                tierPayouts = tierPayouts,
                totalPaid = 950L,
                rolledBack = 0L,
            ),
            openOutcome = LotteryAnnouncer.OpenSummary.Ok(
                seeded = 500L, ticketPrice = 50L, poolAmount = 500L,
            ),
        )

        assertEquals(setOf(7L, 8L, 9L), pushBuilders.keys)
        assert(pushBuilders.getValue(7L).invoke().body.contains("600"))
        assert(pushBuilders.getValue(8L).invoke().body.contains("250"))
        assert(pushBuilders.getValue(9L).invoke().body.contains("100"))
    }

    @Test
    fun `NoTickets cycle still fires the channel broadcast and does not push anyone`() {
        // No winners → no push fan-out, but the open-draw announcement
        // still ships to drive ticket purchases.
        announcer.announceCycle(
            guild, mode = "WEIGHTED",
            priorOutcome = LotteryAnnouncer.PriorOutcome.NoTickets,
            openOutcome = LotteryAnnouncer.OpenSummary.Ok(
                seeded = 500L, ticketPrice = 50L, poolAmount = 500L,
            ),
        )
        verify(exactly = 1) {
            router.sendChannel(any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 0) { router.sendPush(any(), any(), any(), any()) }
    }

    @Test
    fun `NoTickets cycle passes mentions with empty userIds (no winners to filter)`() {
        val mentions = captureMentions()
        announcer.announceCycle(
            guild, mode = "WEIGHTED",
            priorOutcome = LotteryAnnouncer.PriorOutcome.NoTickets,
            openOutcome = LotteryAnnouncer.OpenSummary.Ok(
                seeded = 500L, ticketPrice = 50L, poolAmount = 500L,
            ),
        )
        val m = mentions.captured
        assertEquals(emptyList<Long>(), m.userIds, "no winners → empty mention list")
    }

    @Test
    fun `recordAnnouncement is called via onSent with the lottery id and sent message`() {
        val onSent = slot<(Message) -> Unit>()
        every {
            router.sendChannel(
                guildId = any(),
                route = any(),
                originChannelId = any(),
                message = any(),
                onSent = capture(onSent),
                mentions = any(),
            )
        } just runs

        announcer.announceCycle(
            guild, mode = "WEIGHTED",
            priorOutcome = null,
            openOutcome = LotteryAnnouncer.OpenSummary.Ok(
                lotteryId = 42L, seeded = 500L, ticketPrice = 50L, poolAmount = 500L,
            ),
        )
        // Simulate the router successfully posting.
        val sent = mockk<Message>(relaxed = true) {
            every { idLong } returns 9999L
            every { channel } returns mockk(relaxed = true) { every { idLong } returns 777L }
        }
        onSent.captured.invoke(sent)

        verify(exactly = 1) {
            jackpotLotteryService.recordAnnouncement(
                lotteryId = 42L,
                channelId = 777L,
                messageId = 9999L,
                pool = 500L,
            )
        }
    }

    @Test
    fun `onSent skips recordAnnouncement when openOutcome is Skipped`() {
        val onSent = slot<(Message) -> Unit>()
        every {
            router.sendChannel(
                guildId = any(),
                route = any(),
                originChannelId = any(),
                message = any(),
                onSent = capture(onSent),
                mentions = any(),
            )
        } just runs

        announcer.announceCycle(
            guild, mode = "WEIGHTED",
            priorOutcome = null,
            openOutcome = LotteryAnnouncer.OpenSummary.Skipped,
        )
        onSent.captured.invoke(mockk(relaxed = true))

        verify(exactly = 0) { jackpotLotteryService.recordAnnouncement(any(), any(), any(), any()) }
    }

    // ---- embed body + pings ----

    @Test
    fun `weighted draw mentions every winner in both embed and content`() {
        val build = captureMessageBuilder()

        val payouts = listOf(
            JackpotLotteryService.WinnerPayout(discordId = 1L, ticketCount = 5, amount = 500L),
            JackpotLotteryService.WinnerPayout(discordId = 2L, ticketCount = 3, amount = 300L),
        )
        announcer.announceCycle(
            guild, mode = "WEIGHTED",
            priorOutcome = LotteryAnnouncer.PriorOutcome.WeightedDrawn(
                payouts = payouts, totalPaid = 800L, drained = 1_000L,
            ),
            openOutcome = LotteryAnnouncer.OpenSummary.Ok(
                seeded = 500L, ticketPrice = 50L, poolAmount = 500L,
            ),
        )

        val data = build()
        val embedDescription = data.embeds.single().fields.joinToString(" ") { (it.value ?: "") }
        assertTrue(embedDescription.contains("<@1>"), "embed body mentions winner 1")
        assertTrue(embedDescription.contains("<@2>"), "embed body mentions winner 2")
        assertTrue(data.content.contains("<@1>"), "content pings winner 1")
        assertTrue(data.content.contains("<@2>"), "content pings winner 2")
    }

    @Test
    fun `BelowMinBuyers shows have-need copy and still posts CTA + wide ping`() {
        val build = captureMessageBuilder()

        announcer.announceCycle(
            guild, mode = "WEIGHTED",
            priorOutcome = LotteryAnnouncer.PriorOutcome.BelowMinBuyers(have = 1, need = 2),
            openOutcome = LotteryAnnouncer.OpenSummary.Ok(
                seeded = 500L, ticketPrice = 50L, poolAmount = 500L,
            ),
        )

        val data = build()
        val embedDescription = data.embeds.single().fields.joinToString(" ") { (it.value ?: "") }
        assertTrue(embedDescription.contains("1") && embedDescription.contains("2"))
        assertTrue(embedDescription.lowercase().contains("buyer"))
        // Default ping mode = EVERYONE.
        assertTrue(data.content.contains("@everyone"))
        assertTrue(data.content.contains("/lottery buy"))
        assertFalse(data.content.contains("Congrats:"))
    }

    @Test
    fun `NoTickets renders a distinct copy and still posts CTA + wide ping`() {
        val build = captureMessageBuilder()

        announcer.announceCycle(
            guild, mode = "NUMBER_MATCH",
            priorOutcome = LotteryAnnouncer.PriorOutcome.NoTickets,
            openOutcome = LotteryAnnouncer.OpenSummary.Ok(
                seeded = 500L, ticketPrice = 50L, poolAmount = 500L,
            ),
        )

        val data = build()
        val embedDescription = data.embeds.single().fields.joinToString(" ") { (it.value ?: "") }
        assertTrue(embedDescription.lowercase().contains("no tickets"))
        assertTrue(data.content.contains("@everyone"))
        assertTrue(data.content.contains("/lottery buy"))
        assertFalse(data.content.contains("Congrats:"))
    }

    // ---- ping-mode dispatch ----

    @Test
    fun `PING_MODE=HERE uses @here prefix instead of @everyone`() {
        stubConfig(ConfigDto.Configurations.LOTTERY_PING_MODE, "HERE")
        val build = captureMessageBuilder()

        announcer.announceCycle(
            guild, mode = "WEIGHTED",
            priorOutcome = LotteryAnnouncer.PriorOutcome.NoTickets,
            openOutcome = LotteryAnnouncer.OpenSummary.Ok(
                seeded = 500L, ticketPrice = 50L, poolAmount = 500L,
            ),
        )

        val data = build()
        assertTrue(data.content.contains("@here"))
        assertFalse(data.content.contains("@everyone"))
    }

    @Test
    fun `PING_MODE=OFF still posts CTA but no wide ping`() {
        stubConfig(ConfigDto.Configurations.LOTTERY_PING_MODE, "OFF")
        val build = captureMessageBuilder()

        announcer.announceCycle(
            guild, mode = "WEIGHTED",
            priorOutcome = LotteryAnnouncer.PriorOutcome.NoTickets,
            openOutcome = LotteryAnnouncer.OpenSummary.Ok(
                seeded = 500L, ticketPrice = 50L, poolAmount = 500L,
            ),
        )

        val data = build()
        assertFalse(data.content.contains("@everyone"))
        assertFalse(data.content.contains("@here"))
        assertTrue(data.content.contains("/lottery buy"))
    }

    @Test
    fun `allowed mentions include EVERYONE only when wide ping is in content`() {
        stubConfig(ConfigDto.Configurations.LOTTERY_PING_MODE, "OFF")
        val build = captureMessageBuilder()

        announcer.announceCycle(
            guild, mode = "WEIGHTED",
            priorOutcome = LotteryAnnouncer.PriorOutcome.NoTickets,
            openOutcome = LotteryAnnouncer.OpenSummary.Ok(
                seeded = 500L, ticketPrice = 50L, poolAmount = 500L,
            ),
        )

        val data = build()
        assertTrue(data.allowedMentions.contains(Message.MentionType.USER))
        assertFalse(data.allowedMentions.contains(Message.MentionType.EVERYONE))
    }

    @Test
    fun `allowed mentions include EVERYONE when @everyone is in content`() {
        // No PING_MODE config → default EVERYONE.
        val build = captureMessageBuilder()

        announcer.announceCycle(
            guild, mode = "WEIGHTED",
            priorOutcome = LotteryAnnouncer.PriorOutcome.NoTickets,
            openOutcome = LotteryAnnouncer.OpenSummary.Ok(
                seeded = 500L, ticketPrice = 50L, poolAmount = 500L,
            ),
        )

        val data = build()
        assertTrue(data.allowedMentions.contains(Message.MentionType.EVERYONE))
        assertTrue(data.allowedMentions.contains(Message.MentionType.USER))
    }

    @Test
    fun `match-numbers draw renders winning numbers and per-tier winners`() {
        val build = captureMessageBuilder()

        announcer.announceCycle(
            guild, mode = "NUMBER_MATCH",
            priorOutcome = LotteryAnnouncer.PriorOutcome.MatchDrawn(
                drawnNumbers = listOf(3, 7, 11, 21, 42),
                tierPayouts = listOf(
                    JackpotLotteryService.MatchTierPayout(discordId = 1L, matches = 5, share = 600L),
                    JackpotLotteryService.MatchTierPayout(discordId = 2L, matches = 3, share = 100L),
                ),
                totalPaid = 700L, rolledBack = 300L,
            ),
            openOutcome = LotteryAnnouncer.OpenSummary.Ok(
                seeded = 500L, ticketPrice = 50L, poolAmount = 500L,
            ),
        )

        val data = build()
        val embedDescription = data.embeds.single().fields.joinToString(" ") { (it.value ?: "") }
        assertTrue(embedDescription.contains("3") && embedDescription.contains("42"))
        assertTrue(embedDescription.contains("<@1>"))
        assertTrue(data.content.contains("<@1>"))
    }

    // ---- participation-incentive surfacing ----

    @Test
    fun `weighted open embed renders 'None' for the active incentives field when nothing configured`() {
        val build = captureMessageBuilder()

        announcer.announceCycle(
            guild, mode = "WEIGHTED",
            priorOutcome = null,
            openOutcome = LotteryAnnouncer.OpenSummary.Ok(
                seeded = 500L, ticketPrice = 50L, poolAmount = 500L,
            ),
        )

        val data = build()
        val incentives = data.embeds.single().fields
            .firstOrNull { it.name == LotteryAnnouncer.ACTIVE_INCENTIVES_FIELD }
        assertTrue(incentives != null, "weighted embed always carries the active-incentives field")
        assertEquals("None", incentives!!.value)
    }

    @Test
    fun `weighted open embed lists only configured incentive tiers`() {
        stubConfig(ConfigDto.Configurations.LOTTERY_BULK_TIER1_BUY, "10")
        stubConfig(ConfigDto.Configurations.LOTTERY_BULK_TIER1_BONUS, "3")
        stubConfig(ConfigDto.Configurations.LOTTERY_BULK_TIER2_BUY, "25")
        stubConfig(ConfigDto.Configurations.LOTTERY_BULK_TIER2_BONUS, "8")
        // Tier 3 left unset → must not appear in the embed.
        stubConfig(ConfigDto.Configurations.LOTTERY_MILESTONE1_TICKETS, "50")
        stubConfig(ConfigDto.Configurations.LOTTERY_MILESTONE1_PCT, "10")
        val build = captureMessageBuilder()

        announcer.announceCycle(
            guild, mode = "WEIGHTED",
            priorOutcome = null,
            openOutcome = LotteryAnnouncer.OpenSummary.Ok(
                seeded = 500L, ticketPrice = 50L, poolAmount = 500L,
            ),
        )

        val data = build()
        val incentives = data.embeds.single().fields
            .first { it.name == LotteryAnnouncer.ACTIVE_INCENTIVES_FIELD }
            .value!!
        assertTrue(incentives.contains("buy ≥10"))
        assertTrue(incentives.contains("buy ≥25"))
        assertTrue(incentives.contains("@50 tickets"))
        assertFalse(incentives.contains("Tier 3"), "unset tiers are not surfaced")
    }

    @Test
    fun `number-match open embed omits the active incentives field`() {
        val build = captureMessageBuilder()

        announcer.announceCycle(
            guild, mode = "NUMBER_MATCH",
            priorOutcome = null,
            openOutcome = LotteryAnnouncer.OpenSummary.Ok(
                seeded = 500L, ticketPrice = 50L, poolAmount = 500L,
            ),
        )

        val data = build()
        val incentives = data.embeds.single().fields
            .firstOrNull { it.name == LotteryAnnouncer.ACTIVE_INCENTIVES_FIELD }
        assertTrue(incentives == null, "incentives apply to TICKET_WEIGHTED only")
    }

    @Test
    fun `weighted result embed reports bonus impact and milestone fired`() {
        val build = captureMessageBuilder()
        val payouts = listOf(
            JackpotLotteryService.WinnerPayout(discordId = 1L, ticketCount = 30, amount = 800L),
        )

        announcer.announceCycle(
            guild, mode = "WEIGHTED",
            priorOutcome = LotteryAnnouncer.PriorOutcome.WeightedDrawn(
                payouts = payouts,
                totalPaid = 800L,
                drained = 1_000L,
                bonusTicketsAwarded = 11L,
                highestMilestoneFired = 50L,
            ),
            openOutcome = LotteryAnnouncer.OpenSummary.Ok(
                seeded = 500L, ticketPrice = 50L, poolAmount = 500L,
            ),
        )

        val data = build()
        val yesterday = data.embeds.single().fields
            .first { it.name == LotteryAnnouncer.YESTERDAYS_DRAW_FIELD }
            .value!!
        assertTrue(yesterday.contains("11"), "bonus ticket count surfaced: $yesterday")
        assertTrue(yesterday.contains("50"), "milestone threshold surfaced: $yesterday")
        assertTrue(yesterday.lowercase().contains("milestone"))
    }

    @Test
    fun `weighted result embed omits bonus impact line when nothing happened`() {
        val build = captureMessageBuilder()
        val payouts = listOf(
            JackpotLotteryService.WinnerPayout(discordId = 1L, ticketCount = 5, amount = 500L),
        )

        announcer.announceCycle(
            guild, mode = "WEIGHTED",
            priorOutcome = LotteryAnnouncer.PriorOutcome.WeightedDrawn(
                payouts = payouts, totalPaid = 500L, drained = 500L,
            ),
            openOutcome = LotteryAnnouncer.OpenSummary.Ok(
                seeded = 500L, ticketPrice = 50L, poolAmount = 500L,
            ),
        )

        val data = build()
        val yesterday = data.embeds.single().fields
            .first { it.name == LotteryAnnouncer.YESTERDAYS_DRAW_FIELD }
            .value!!
        assertFalse(yesterday.contains("Milestones"), "no milestone fired, no line surfaced")
        assertFalse(yesterday.contains("Bulk bonus impact"), "no bonuses awarded, no line surfaced")
    }

    // ---- refreshAnnouncement (unchanged code path; not routed through NotificationRouter) ----

    private fun openLottery(
        id: Long = 1L,
        poolAmount: Long = 1_500L,
        announcedPoolAmount: Long? = 1_000L,
        announcementMessageId: Long? = 999L,
        announcementChannelId: Long? = 777L,
        mode: String = JackpotLotteryDto.MODE_NUMBER_MATCH,
    ) = JackpotLotteryDto(
        id = id,
        guildId = guildId,
        ticketPrice = 50L,
        poolAmount = poolAmount,
        mode = mode,
    ).also {
        it.announcementMessageId = announcementMessageId
        it.announcementChannelId = announcementChannelId
        it.announcedPoolAmount = announcedPoolAmount
    }

    @Nested
    inner class RefreshAnnouncement {

        @Test
        fun `short-circuits when announcementMessageId is null`() {
            val lottery = openLottery(announcementMessageId = null)

            announcer.refreshAnnouncement(guild, lottery)

            verify(exactly = 0) { guild.getTextChannelById(any<Long>()) }
            verify(exactly = 0) { jackpotLotteryService.updateAnnouncedPool(any(), any()) }
        }

        @Test
        fun `short-circuits when announcementChannelId is null`() {
            val lottery = openLottery(announcementChannelId = null)

            announcer.refreshAnnouncement(guild, lottery)

            verify(exactly = 0) { guild.getTextChannelById(any<Long>()) }
        }

        @Test
        fun `short-circuits when announcedPoolAmount equals poolAmount`() {
            val lottery = openLottery(poolAmount = 1_000L, announcedPoolAmount = 1_000L)

            announcer.refreshAnnouncement(guild, lottery)

            verify(exactly = 0) { guild.getTextChannelById(any<Long>()) }
        }

        @Test
        fun `clears announcement ref when channel vanishes`() {
            every { guild.getTextChannelById(777L) } returns null
            val lottery = openLottery()

            announcer.refreshAnnouncement(guild, lottery)

            verify(exactly = 1) { jackpotLotteryService.clearAnnouncement(1L) }
        }

        @Test
        fun `edits embed and updates watermark when pool has grown`() {
            every { guild.getTextChannelById(777L) } returns channel

            val previousEmbed = EmbedBuilder()
                .setTitle("🎟️ Daily Lottery — Test Guild")
                .addField(
                    LotteryAnnouncer.YESTERDAYS_DRAW_FIELD,
                    "No tickets bought. Seed returned to jackpot.",
                    false,
                )
                .addField(
                    LotteryAnnouncer.TODAYS_DRAW_FIELD,
                    "**1000** credits in the pool · Ticket: **50** · Mode: **Pick 5 of 49** · Closes 24h.",
                    false,
                )
                .build()
            val message: Message = mockk(relaxed = true)
            every { message.embeds } returns listOf(previousEmbed)

            val retrieveAction: RestAction<Message> = mockk(relaxed = true)
            every { channel.retrieveMessageById(999L) } returns retrieveAction
            every {
                retrieveAction.queue(any<java.util.function.Consumer<Message>>(), any())
            } answers {
                firstArg<java.util.function.Consumer<Message>>().accept(message)
            }

            val editAction: MessageEditAction = mockk(relaxed = true)
            val editedEmbedSlot = slot<MessageEmbed>()
            every { message.editMessageEmbeds(capture(editedEmbedSlot)) } returns editAction
            every { editAction.setComponents(any<Collection<MessageTopLevelComponent>>()) } returns editAction
            every {
                editAction.queue(any<java.util.function.Consumer<Message>>(), any())
            } answers {
                firstArg<java.util.function.Consumer<Message>>().accept(message)
            }

            val lottery = openLottery(poolAmount = 1_500L, announcedPoolAmount = 1_000L)
            announcer.refreshAnnouncement(guild, lottery)

            val todayField = editedEmbedSlot.captured.fields.first { it.name == LotteryAnnouncer.TODAYS_DRAW_FIELD }
            assertTrue(todayField.value!!.contains("1500"), "embed should show updated pool: ${todayField.value}")
            assertFalse(todayField.value!!.contains("1000"), "embed should not show stale pool: ${todayField.value}")

            val yesterdayField = editedEmbedSlot.captured.fields.first { it.name == LotteryAnnouncer.YESTERDAYS_DRAW_FIELD }
            assertTrue(yesterdayField.value!!.contains("No tickets"), "yesterday's field should be preserved")

            verify(exactly = 1) { jackpotLotteryService.updateAnnouncedPool(1L, 1_500L) }
        }

        @Test
        fun `weighted refresh renders the weighted mode label, not the number-match one`() {
            every { guild.getTextChannelById(777L) } returns channel

            val previousEmbed = EmbedBuilder()
                .setTitle("🎟️ Daily Lottery — Test Guild")
                .addField(
                    LotteryAnnouncer.TODAYS_DRAW_FIELD,
                    "**1000** credits in the pool · Ticket: **50** · Mode: **Top-3 weighted** · Closes 24h.",
                    false,
                )
                .build()
            val message: Message = mockk(relaxed = true)
            every { message.embeds } returns listOf(previousEmbed)

            val retrieveAction: RestAction<Message> = mockk(relaxed = true)
            every { channel.retrieveMessageById(999L) } returns retrieveAction
            every {
                retrieveAction.queue(any<java.util.function.Consumer<Message>>(), any())
            } answers {
                firstArg<java.util.function.Consumer<Message>>().accept(message)
            }

            val editAction: MessageEditAction = mockk(relaxed = true)
            val editedEmbedSlot = slot<MessageEmbed>()
            val componentsSlot = slot<Collection<MessageTopLevelComponent>>()
            every { message.editMessageEmbeds(capture(editedEmbedSlot)) } returns editAction
            every { editAction.setComponents(capture(componentsSlot)) } returns editAction
            every {
                editAction.queue(any<java.util.function.Consumer<Message>>(), any())
            } answers {
                firstArg<java.util.function.Consumer<Message>>().accept(message)
            }

            val lottery = openLottery(
                poolAmount = 2_000L,
                announcedPoolAmount = 1_000L,
                mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
            )
            announcer.refreshAnnouncement(guild, lottery)

            val todayField = editedEmbedSlot.captured.fields.first {
                it.name == LotteryAnnouncer.TODAYS_DRAW_FIELD
            }
            assertTrue(
                todayField.value!!.contains("Top-3 weighted"),
                "weighted refresh should render the weighted label: ${todayField.value}",
            )
            assertFalse(
                todayField.value!!.contains("Pick 5 of 49"),
                "weighted refresh should not render the number-match label: ${todayField.value}",
            )
            assertTrue(
                componentsSlot.captured.isNotEmpty(),
                "weighted refresh should preserve the Buy Tickets action row, " +
                    "got: ${componentsSlot.captured}",
            )
        }

        @Test
        fun `clears announcement ref on UNKNOWN_MESSAGE error`() {
            every { guild.getTextChannelById(777L) } returns channel

            val retrieveAction: RestAction<Message> = mockk(relaxed = true)
            every { channel.retrieveMessageById(999L) } returns retrieveAction

            val errorResponse: ErrorResponseException = mockk(relaxed = true)
            every { errorResponse.errorResponse } returns ErrorResponse.UNKNOWN_MESSAGE

            every {
                retrieveAction.queue(any<java.util.function.Consumer<Message>>(), any())
            } answers {
                secondArg<java.util.function.Consumer<Throwable>>().accept(errorResponse)
            }

            val lottery = openLottery()
            announcer.refreshAnnouncement(guild, lottery)

            verify(exactly = 1) { jackpotLotteryService.clearAnnouncement(1L) }
            verify(exactly = 0) { jackpotLotteryService.updateAnnouncedPool(any(), any()) }
        }
    }
}
