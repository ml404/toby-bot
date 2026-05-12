package bot.toby.scheduling

import database.dto.ConfigDto
import database.dto.JackpotLotteryDto
import database.service.ConfigService
import database.service.JackpotLotteryService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.SelfMember
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.requests.ErrorResponse
import net.dv8tion.jda.api.requests.RestAction
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction
import net.dv8tion.jda.api.requests.restaction.MessageEditAction
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The announcer is a thin shim — most surface is channel resolution +
 * embed shape, with a single side-effect (channel.sendMessageEmbeds).
 * Tests focus on:
 *   - Resolution chain: LOTTERY_CHANNEL → LEADERBOARD_CHANNEL → system
 *   - Embed body: per-PriorOutcome variant produces distinct copy
 *   - Content: wide ping prefix from LOTTERY_PING_MODE (off / here /
 *     everyone), CTA with `/lottery buy` mention, winner pings
 *   - AllowedMentions: USER always, EVERYONE only when content has the
 *     wide ping (otherwise Discord silently strips it)
 *   - No-op when no writable channel resolves
 */
class LotteryAnnouncerTest {

    private val guildId = 100L
    private lateinit var configService: ConfigService
    private lateinit var jackpotLotteryService: JackpotLotteryService
    private lateinit var guild: Guild
    private lateinit var jda: JDA
    private lateinit var bot: SelfMember
    private lateinit var channel: TextChannel
    private lateinit var sendAction: MessageCreateAction
    private lateinit var announcer: LotteryAnnouncer

    @BeforeEach
    fun setup() {
        configService = mockk(relaxed = true)
        jackpotLotteryService = mockk(relaxed = true)
        guild = mockk(relaxed = true)
        jda = mockk(relaxed = true)
        bot = mockk(relaxed = true)
        channel = mockk(relaxed = true)
        sendAction = mockk(relaxed = true)

        every { guild.idLong } returns guildId
        every { guild.id } returns guildId.toString()
        every { guild.name } returns "Test Guild"
        every { guild.selfMember } returns bot
        every { guild.jda } returns jda
        // Slash-command id resolution: stub the full chain to return an
        // empty command list so `firstOrNull { name == "lottery" }` is
        // null and the announcer falls back to plain `/lottery buy` text.
        // Using an explicit stub rather than `throws` because mockk's
        // handling of default interface methods (retrieveCommands has a
        // default impl) can vary between versions.
        val restAction: RestAction<List<Command>> = mockk(relaxed = true)
        every { jda.retrieveCommands() } returns restAction
        every { restAction.complete() } returns emptyList()
        every { bot.hasPermission(channel, *anyVararg<Permission>()) } returns true
        every { channel.id } returns "777"
        every { channel.idLong } returns 777L
        every { channel.sendMessageEmbeds(any<MessageEmbed>()) } returns sendAction
        every { sendAction.addContent(any()) } returns sendAction
        every { sendAction.setAllowedMentions(any<Collection<Message.MentionType>>()) } returns sendAction
        every { sendAction.queue() } just Runs
        // The two-callback `queue(success, failure)` overload is used by
        // [LotteryAnnouncer.announceCycle] to capture the posted message
        // id; tests don't drive the success path so a no-op stub keeps
        // verification on `channel.sendMessageEmbeds` working unchanged.
        every {
            sendAction.queue(any<java.util.function.Consumer<Message>>(), any())
        } just Runs

        announcer = LotteryAnnouncer(configService, jackpotLotteryService)
    }

    private fun stubChannelConfig(key: ConfigDto.Configurations, value: String?) {
        every {
            configService.getConfigByName(key.configValue, guildId.toString())
        } returns value?.let {
            ConfigDto(name = key.configValue, value = it, guildId = guildId.toString())
        }
    }

    // ---- channel resolution ----

    @Test
    fun `resolves LOTTERY_CHANNEL first when set and writable`() {
        stubChannelConfig(ConfigDto.Configurations.LOTTERY_CHANNEL, "777")
        every { guild.getTextChannelById(777L) } returns channel

        announcer.announceCycle(
            guild,
            mode = "WEIGHTED",
            priorOutcome = LotteryAnnouncer.PriorOutcome.NoTickets,
            openOutcome = LotteryAnnouncer.OpenSummary.Ok(
                seeded = 1_000L, ticketPrice = 50L, poolAmount = 1_000L,
            ),
        )

        verify(exactly = 1) { channel.sendMessageEmbeds(any<MessageEmbed>()) }
        // Not even consulted — LOTTERY_CHANNEL hit.
        verify(exactly = 0) {
            configService.getConfigByName(
                ConfigDto.Configurations.LEADERBOARD_CHANNEL.configValue, guildId.toString()
            )
        }
    }

    @Test
    fun `falls back to LEADERBOARD_CHANNEL when LOTTERY_CHANNEL is missing`() {
        stubChannelConfig(ConfigDto.Configurations.LOTTERY_CHANNEL, null)
        stubChannelConfig(ConfigDto.Configurations.LEADERBOARD_CHANNEL, "888")
        every { guild.getTextChannelById(888L) } returns channel

        announcer.announceCycle(
            guild, mode = "NUMBER_MATCH",
            priorOutcome = null,
            openOutcome = LotteryAnnouncer.OpenSummary.Ok(
                seeded = 500L, ticketPrice = 50L, poolAmount = 500L,
            ),
        )

        verify(exactly = 1) { channel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `falls back to system channel when neither config resolves`() {
        stubChannelConfig(ConfigDto.Configurations.LOTTERY_CHANNEL, null)
        stubChannelConfig(ConfigDto.Configurations.LEADERBOARD_CHANNEL, null)
        every { guild.systemChannel } returns channel

        announcer.announceCycle(
            guild, mode = "WEIGHTED",
            priorOutcome = null,
            openOutcome = LotteryAnnouncer.OpenSummary.Skipped,
        )

        verify(exactly = 1) { channel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `no-op when no writable channel resolves`() {
        stubChannelConfig(ConfigDto.Configurations.LOTTERY_CHANNEL, null)
        stubChannelConfig(ConfigDto.Configurations.LEADERBOARD_CHANNEL, null)
        every { guild.systemChannel } returns null

        announcer.announceCycle(
            guild, mode = "WEIGHTED",
            priorOutcome = null,
            openOutcome = LotteryAnnouncer.OpenSummary.Skipped,
        )

        verify(exactly = 0) { channel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    // ---- embed body + pings ----

    @Test
    fun `weighted draw mentions every winner and pings them in addContent`() {
        stubChannelConfig(ConfigDto.Configurations.LOTTERY_CHANNEL, "777")
        every { guild.getTextChannelById(777L) } returns channel
        val embedSlot = slot<MessageEmbed>()
        val contentSlot = slot<String>()
        every { channel.sendMessageEmbeds(capture(embedSlot)) } returns sendAction
        every { sendAction.addContent(capture(contentSlot)) } returns sendAction

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

        val embedDescription = embedSlot.captured.fields.joinToString(" ") { (it.value ?: "") }
        assertTrue(embedDescription.contains("<@1>"), "embed body mentions winner 1")
        assertTrue(embedDescription.contains("<@2>"), "embed body mentions winner 2")
        assertTrue(contentSlot.captured.contains("<@1>"), "addContent pings winner 1")
        assertTrue(contentSlot.captured.contains("<@2>"), "addContent pings winner 2")
    }

    @Test
    fun `BelowMinBuyers shows have-need copy and still posts CTA + wide ping`() {
        stubChannelConfig(ConfigDto.Configurations.LOTTERY_CHANNEL, "777")
        every { guild.getTextChannelById(777L) } returns channel
        val embedSlot = slot<MessageEmbed>()
        val contentSlot = slot<String>()
        every { channel.sendMessageEmbeds(capture(embedSlot)) } returns sendAction
        every { sendAction.addContent(capture(contentSlot)) } returns sendAction

        announcer.announceCycle(
            guild, mode = "WEIGHTED",
            priorOutcome = LotteryAnnouncer.PriorOutcome.BelowMinBuyers(have = 1, need = 2),
            openOutcome = LotteryAnnouncer.OpenSummary.Ok(
                seeded = 500L, ticketPrice = 50L, poolAmount = 500L,
            ),
        )

        val embedDescription = embedSlot.captured.fields.joinToString(" ") { (it.value ?: "") }
        assertTrue(embedDescription.contains("1") && embedDescription.contains("2"))
        assertTrue(embedDescription.lowercase().contains("buyer"))
        // Default ping mode = EVERYONE → wide ping + CTA still goes out
        // (this is exactly the case where we want to nudge people to buy).
        assertTrue(contentSlot.captured.contains("@everyone"))
        assertTrue(contentSlot.captured.contains("/lottery buy"))
        // No winners → no per-winner mention line.
        assertFalse(contentSlot.captured.contains("Congrats:"))
    }

    @Test
    fun `NoTickets renders a distinct copy and still posts CTA + wide ping`() {
        stubChannelConfig(ConfigDto.Configurations.LOTTERY_CHANNEL, "777")
        every { guild.getTextChannelById(777L) } returns channel
        val embedSlot = slot<MessageEmbed>()
        val contentSlot = slot<String>()
        every { channel.sendMessageEmbeds(capture(embedSlot)) } returns sendAction
        every { sendAction.addContent(capture(contentSlot)) } returns sendAction

        announcer.announceCycle(
            guild, mode = "NUMBER_MATCH",
            priorOutcome = LotteryAnnouncer.PriorOutcome.NoTickets,
            openOutcome = LotteryAnnouncer.OpenSummary.Ok(
                seeded = 500L, ticketPrice = 50L, poolAmount = 500L,
            ),
        )

        val embedDescription = embedSlot.captured.fields.joinToString(" ") { (it.value ?: "") }
        assertTrue(embedDescription.lowercase().contains("no tickets"))
        assertTrue(contentSlot.captured.contains("@everyone"))
        assertTrue(contentSlot.captured.contains("/lottery buy"))
        assertFalse(contentSlot.captured.contains("Congrats:"))
    }

    // ---- ping-mode dispatch ----

    @Test
    fun `PING_MODE=HERE uses @here prefix instead of @everyone`() {
        stubChannelConfig(ConfigDto.Configurations.LOTTERY_CHANNEL, "777")
        stubChannelConfig(ConfigDto.Configurations.LOTTERY_PING_MODE, "HERE")
        every { guild.getTextChannelById(777L) } returns channel
        val contentSlot = slot<String>()
        every { sendAction.addContent(capture(contentSlot)) } returns sendAction

        announcer.announceCycle(
            guild, mode = "WEIGHTED",
            priorOutcome = LotteryAnnouncer.PriorOutcome.NoTickets,
            openOutcome = LotteryAnnouncer.OpenSummary.Ok(
                seeded = 500L, ticketPrice = 50L, poolAmount = 500L,
            ),
        )

        assertTrue(contentSlot.captured.contains("@here"))
        assertFalse(contentSlot.captured.contains("@everyone"))
    }

    @Test
    fun `PING_MODE=OFF still posts CTA but no wide ping`() {
        stubChannelConfig(ConfigDto.Configurations.LOTTERY_CHANNEL, "777")
        stubChannelConfig(ConfigDto.Configurations.LOTTERY_PING_MODE, "OFF")
        every { guild.getTextChannelById(777L) } returns channel
        val contentSlot = slot<String>()
        every { sendAction.addContent(capture(contentSlot)) } returns sendAction

        announcer.announceCycle(
            guild, mode = "WEIGHTED",
            priorOutcome = LotteryAnnouncer.PriorOutcome.NoTickets,
            openOutcome = LotteryAnnouncer.OpenSummary.Ok(
                seeded = 500L, ticketPrice = 50L, poolAmount = 500L,
            ),
        )

        assertFalse(contentSlot.captured.contains("@everyone"))
        assertFalse(contentSlot.captured.contains("@here"))
        assertTrue(contentSlot.captured.contains("/lottery buy"))
    }

    @Test
    fun `allowed mentions include EVERYONE only when wide ping is in content`() {
        stubChannelConfig(ConfigDto.Configurations.LOTTERY_CHANNEL, "777")
        stubChannelConfig(ConfigDto.Configurations.LOTTERY_PING_MODE, "OFF")
        every { guild.getTextChannelById(777L) } returns channel
        val mentionsSlot = slot<Collection<Message.MentionType>>()
        every { sendAction.setAllowedMentions(capture(mentionsSlot)) } returns sendAction

        announcer.announceCycle(
            guild, mode = "WEIGHTED",
            priorOutcome = LotteryAnnouncer.PriorOutcome.NoTickets,
            openOutcome = LotteryAnnouncer.OpenSummary.Ok(
                seeded = 500L, ticketPrice = 50L, poolAmount = 500L,
            ),
        )

        assertTrue(mentionsSlot.captured.contains(Message.MentionType.USER))
        assertFalse(mentionsSlot.captured.contains(Message.MentionType.EVERYONE))
    }

    @Test
    fun `allowed mentions include EVERYONE when @everyone is in content`() {
        stubChannelConfig(ConfigDto.Configurations.LOTTERY_CHANNEL, "777")
        // No PING_MODE set → default EVERYONE.
        every { guild.getTextChannelById(777L) } returns channel
        val mentionsSlot = slot<Collection<Message.MentionType>>()
        every { sendAction.setAllowedMentions(capture(mentionsSlot)) } returns sendAction

        announcer.announceCycle(
            guild, mode = "WEIGHTED",
            priorOutcome = LotteryAnnouncer.PriorOutcome.NoTickets,
            openOutcome = LotteryAnnouncer.OpenSummary.Ok(
                seeded = 500L, ticketPrice = 50L, poolAmount = 500L,
            ),
        )

        assertTrue(mentionsSlot.captured.contains(Message.MentionType.EVERYONE))
        assertTrue(mentionsSlot.captured.contains(Message.MentionType.USER))
    }

    @Test
    fun `match-numbers draw renders winning numbers and per-tier winners`() {
        stubChannelConfig(ConfigDto.Configurations.LOTTERY_CHANNEL, "777")
        every { guild.getTextChannelById(777L) } returns channel
        val embedSlot = slot<MessageEmbed>()
        val contentSlot = slot<String>()
        every { channel.sendMessageEmbeds(capture(embedSlot)) } returns sendAction
        every { sendAction.addContent(capture(contentSlot)) } returns sendAction

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

        val embedDescription = embedSlot.captured.fields.joinToString(" ") { (it.value ?: "") }
        assertTrue(embedDescription.contains("3") && embedDescription.contains("42"))
        assertTrue(embedDescription.contains("<@1>"))
        assertTrue(contentSlot.captured.contains("<@1>"))
    }

    // ---- refreshAnnouncement ----

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
            // Regression: rebuildWithUpdatedTodaysDraw used to pass
            // lottery.mode (the DTO column value "TICKET_WEIGHTED")
            // straight to renderOpenSummary, which expects the runtime
            // constant "WEIGHTED". The comparison fell through and
            // emitted "Pick 5 of 49" for refreshed weighted draws.
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
            every { message.editMessageEmbeds(capture(editedEmbedSlot)) } returns editAction
            every { editAction.setComponents(any<Collection<MessageTopLevelComponent>>()) } returns editAction
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
