package bot.toby.modal.modals

import bot.toby.scheduling.LotteryAnnouncer
import core.modal.ModalContext
import database.dto.JackpotLotteryDto
import database.service.JackpotLotteryService
import database.service.JackpotLotteryService.OpenOutcome
import database.service.LotteryHelper
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.modals.ModalMapping
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Pins the modal's announce wiring. Before this regression guard, the
 * admin lottery-open flow opened the lottery silently — only an
 * ephemeral "Opened" message went to the admin, and no embed reached
 * the announce channel. Tests verify:
 *  - Successful open routes through `LotteryAnnouncer.announceCycle`
 *    with `mode = WEIGHTED`, no prior outcome, and the open summary
 *    populated from the service result.
 *  - Failure outcomes (AlreadyOpen / EmptyPool / InvalidParams) do
 *    NOT call announceCycle — matching pre-fix behaviour for those
 *    branches.
 */
class JackpotAdminModalTest {

    private val guildId = 100L

    private lateinit var jackpotLotteryService: JackpotLotteryService
    private lateinit var lotteryAnnouncer: LotteryAnnouncer
    private lateinit var modal: JackpotAdminModal
    private lateinit var ctx: ModalContext
    private lateinit var event: ModalInteractionEvent
    private lateinit var hook: InteractionHook
    private lateinit var guild: Guild
    private val messageSlot = slot<String>()

    @BeforeEach
    fun setup() {
        jackpotLotteryService = mockk()
        lotteryAnnouncer = mockk(relaxed = true)
        modal = JackpotAdminModal(jackpotLotteryService, lotteryAnnouncer)
        hook = mockk(relaxed = true)
        event = mockk(relaxed = true)
        guild = mockk(relaxed = true) {
            every { idLong } returns guildId
        }

        every { event.hook } returns hook

        ctx = mockk {
            every { this@mockk.event } returns this@JackpotAdminModalTest.event
            // Qualify the RHS — inside `mockk<ModalContext> { … }` the
            // receiver is the ctx mock, which has its own `guild`
            // property. An unqualified `guild` would resolve to the
            // un-stubbed mock getter rather than the outer test field
            // and blow up with "no answer provided for ModalContext.getGuild()".
            every { this@mockk.guild } returns this@JackpotAdminModalTest.guild
        }

        @Suppress("UNCHECKED_CAST")
        val sendAction = mockk<WebhookMessageCreateAction<Message>>(relaxed = true)
        every { hook.sendMessage(capture(messageSlot)) } returns sendAction
        every { sendAction.setEphemeral(true) } returns sendAction
        every { sendAction.queue() } just Runs

        // Default field stubs — happy path; individual tests override.
        stubField(JackpotAdminModal.FIELD_TICKET_PRICE, "100")
        stubField(JackpotAdminModal.FIELD_DURATION_HOURS, "24")
        stubField(JackpotAdminModal.FIELD_WINNER_COUNT, "3")
        stubField(JackpotAdminModal.FIELD_DRAIN_PCT, "10")
    }

    private fun stubField(fieldName: String, value: String) {
        val mapping = mockk<ModalMapping> { every { asString } returns value }
        every { event.getValue(fieldName) } returns mapping
    }

    @Test
    fun `name is jackpot_admin_lottery_open`() {
        assertEquals("jackpot_admin_lottery_open", modal.name)
    }

    @Test
    fun `handle on OpenOutcome Ok calls LotteryAnnouncer announceCycle with mode=WEIGHTED`() {
        // The lottery row carries the data the announce needs — id,
        // ticketPrice, poolAmount — so the embed surfaces the live
        // numbers and the watermark can be persisted via the onSent
        // callback inside announceCycle.
        val opened = JackpotLotteryDto(
            id = 7L, guildId = guildId, ticketPrice = 100L, poolAmount = 1_000L,
            winnerCount = 3, status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
        )
        every {
            jackpotLotteryService.openLottery(guildId, 100L, 24L, 3, 0.10)
        } returns OpenOutcome.Ok(lottery = opened, seeded = 1_000L)

        modal.handle(ctx, 0)

        // Ephemeral confirmation still goes to the admin.
        assertTrue(messageSlot.captured.contains("Opened lottery"))
        // And announce gets posted with the right shape.
        verify(exactly = 1) {
            lotteryAnnouncer.announceCycle(
                guild = guild,
                mode = LotteryHelper.MODE_WEIGHTED,
                priorOutcome = null,
                openOutcome = match<LotteryAnnouncer.OpenSummary.Ok> {
                    it.lotteryId == 7L &&
                        it.seeded == 1_000L &&
                        it.ticketPrice == 100L &&
                        it.poolAmount == 1_000L
                },
            )
        }
    }

    @Test
    fun `handle on AlreadyOpen does not call announceCycle`() {
        every {
            jackpotLotteryService.openLottery(guildId, 100L, 24L, 3, 0.10)
        } returns OpenOutcome.AlreadyOpen

        modal.handle(ctx, 0)

        assertTrue(messageSlot.captured.contains("already open"))
        verify(exactly = 0) {
            lotteryAnnouncer.announceCycle(any(), any(), any(), any())
        }
    }

    @Test
    fun `handle on EmptyPool does not call announceCycle`() {
        every {
            jackpotLotteryService.openLottery(guildId, 100L, 24L, 3, 0.10)
        } returns OpenOutcome.EmptyPool

        modal.handle(ctx, 0)

        assertTrue(messageSlot.captured.contains("Jackpot pool is empty"))
        verify(exactly = 0) {
            lotteryAnnouncer.announceCycle(any(), any(), any(), any())
        }
    }

    @Test
    fun `handle on InvalidParams does not call announceCycle`() {
        every {
            jackpotLotteryService.openLottery(guildId, 100L, 24L, 3, 0.10)
        } returns OpenOutcome.InvalidParams("ticket price must be > 0")

        modal.handle(ctx, 0)

        assertTrue(messageSlot.captured.contains("Invalid params"))
        verify(exactly = 0) {
            lotteryAnnouncer.announceCycle(any(), any(), any(), any())
        }
    }

    @Test
    fun `handle swallows announceCycle failures so the open is not rolled back`() {
        // The open already succeeded by the time we try to announce.
        // If JDA throws (channel resolution failure, permission, etc),
        // the lottery must still be considered opened — the admin
        // gets their ephemeral confirmation and life goes on.
        val opened = JackpotLotteryDto(
            id = 7L, guildId = guildId, ticketPrice = 100L, poolAmount = 1_000L,
            winnerCount = 3, status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
        )
        every {
            jackpotLotteryService.openLottery(guildId, 100L, 24L, 3, 0.10)
        } returns OpenOutcome.Ok(lottery = opened, seeded = 1_000L)
        every {
            lotteryAnnouncer.announceCycle(any(), any(), any(), any())
        } throws RuntimeException("channel resolution failed")

        // Should not throw — the runCatching in the modal swallows.
        modal.handle(ctx, 0)

        assertTrue(messageSlot.captured.contains("Opened lottery"))
    }
}
