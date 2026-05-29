package bot.toby.modal.modals

import core.modal.ModalContext
import database.service.lottery.JackpotLotteryService
import database.service.lottery.JackpotLotteryService.BuyOutcome
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.modals.ModalMapping
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LotteryBuyModalTest {

    private lateinit var jackpotLotteryService: JackpotLotteryService
    private lateinit var modal: LotteryBuyModal
    private lateinit var ctx: ModalContext
    private lateinit var event: ModalInteractionEvent
    private lateinit var hook: InteractionHook
    private val messageSlot = slot<String>()

    @BeforeEach
    fun setup() {
        jackpotLotteryService = mockk()
        modal = LotteryBuyModal(jackpotLotteryService)
        hook = mockk(relaxed = true)
        event = mockk(relaxed = true)

        val guild = mockk<Guild> {
            every { idLong } returns 100L
        }
        val user = mockk<User> {
            every { idLong } returns 42L
        }

        every { event.user } returns user
        every { event.hook } returns hook

        ctx = mockk {
            every { this@mockk.event } returns this@LotteryBuyModalTest.event
            every { this@mockk.guild } returns guild
        }

        @Suppress("UNCHECKED_CAST")
        val sendAction = mockk<WebhookMessageCreateAction<Message>>(relaxed = true)
        every { hook.sendMessage(capture(messageSlot)) } returns sendAction
        every { sendAction.setEphemeral(true) } returns sendAction
        every { sendAction.queue() } just Runs
    }

    @Test
    fun `name is lottery_buy`() {
        assertEquals("lottery_buy", modal.name)
    }

    @Test
    fun `replies with error when count is not a number`() {
        val mapping = mockk<ModalMapping> { every { asString } returns "abc" }
        every { event.getValue("count") } returns mapping

        modal.handle(ctx, 0)

        assertTrue(messageSlot.captured.contains("valid number"))
    }

    @Test
    fun `replies with success on BuyOutcome Ok`() {
        val mapping = mockk<ModalMapping> { every { asString } returns "5" }
        every { event.getValue("count") } returns mapping
        every { jackpotLotteryService.buyTickets(100L, 42L, 5) } returns BuyOutcome.Ok(
            ticketCount = 10, totalSpent = 250L, newBalance = 750L, newPool = 2_000L,
        )

        modal.handle(ctx, 0)

        assertTrue(messageSlot.captured.contains("Bought **5**"))
        assertTrue(messageSlot.captured.contains("10"))
        assertTrue(messageSlot.captured.contains("2000"))
    }

    @Test
    fun `surfaces bonus tickets awarded on a bulk buy`() {
        val mapping = mockk<ModalMapping> { every { asString } returns "5" }
        every { event.getValue("count") } returns mapping
        every { jackpotLotteryService.buyTickets(100L, 42L, 5) } returns BuyOutcome.Ok(
            ticketCount = 10, totalSpent = 250L, newBalance = 750L, newPool = 2_000L,
            bonusTicketsGranted = 2L,
        )

        modal.handle(ctx, 0)

        val msg = messageSlot.captured
        assertTrue(msg.contains("bonus"), "expected message to mention bonus tickets: $msg")
        assertTrue(msg.contains("2"), "expected message to mention the bonus count: $msg")
    }

    @Test
    fun `omits bonus mention when no bonus was awarded`() {
        val mapping = mockk<ModalMapping> { every { asString } returns "1" }
        every { event.getValue("count") } returns mapping
        every { jackpotLotteryService.buyTickets(100L, 42L, 1) } returns BuyOutcome.Ok(
            ticketCount = 1, totalSpent = 50L, newBalance = 950L, newPool = 1_000L,
            bonusTicketsGranted = 0L,
        )

        modal.handle(ctx, 0)

        assertTrue(!messageSlot.captured.contains("bonus"), "did not expect a bonus mention: ${messageSlot.captured}")
    }

    @Test
    fun `surfaces milestone jackpot draws triggered by the purchase`() {
        val mapping = mockk<ModalMapping> { every { asString } returns "5" }
        every { event.getValue("count") } returns mapping
        every { jackpotLotteryService.buyTickets(100L, 42L, 5) } returns BuyOutcome.Ok(
            ticketCount = 10, totalSpent = 250L, newBalance = 750L, newPool = 2_000L,
            milestoneBonuses = listOf(
                BuyOutcome.MilestoneBonus(threshold = 50L, creditsAdded = 500L),
                BuyOutcome.MilestoneBonus(threshold = 100L, creditsAdded = 750L),
            ),
        )

        modal.handle(ctx, 0)

        val msg = messageSlot.captured
        assertTrue(msg.contains("Milestone"), "expected message to mention milestone: $msg")
        assertTrue(msg.contains("50"), "expected first milestone threshold: $msg")
        assertTrue(msg.contains("500"), "expected first milestone credits: $msg")
        assertTrue(msg.contains("100"), "expected second milestone threshold: $msg")
        assertTrue(msg.contains("750"), "expected second milestone credits: $msg")
    }

    @Test
    fun `replies with error on NoOpenLottery`() {
        val mapping = mockk<ModalMapping> { every { asString } returns "1" }
        every { event.getValue("count") } returns mapping
        every { jackpotLotteryService.buyTickets(100L, 42L, 1) } returns BuyOutcome.NoOpenLottery

        modal.handle(ctx, 0)

        assertTrue(messageSlot.captured.contains("No lottery is open"))
    }

    @Test
    fun `replies with error on Insufficient funds`() {
        val mapping = mockk<ModalMapping> { every { asString } returns "10" }
        every { event.getValue("count") } returns mapping
        every { jackpotLotteryService.buyTickets(100L, 42L, 10) } returns BuyOutcome.Insufficient(
            have = 100L, need = 500L,
        )

        modal.handle(ctx, 0)

        assertTrue(messageSlot.captured.contains("100"))
        assertTrue(messageSlot.captured.contains("500"))
    }

    @Test
    fun `replies with error on InvalidCount`() {
        val mapping = mockk<ModalMapping> { every { asString } returns "0" }
        every { event.getValue("count") } returns mapping
        every { jackpotLotteryService.buyTickets(100L, 42L, 0) } returns BuyOutcome.InvalidCount(0)

        modal.handle(ctx, 0)

        assertTrue(messageSlot.captured.contains("between 1 and 1,000"))
    }

    @Test
    fun `replies with error on UnknownUser`() {
        val mapping = mockk<ModalMapping> { every { asString } returns "1" }
        every { event.getValue("count") } returns mapping
        every { jackpotLotteryService.buyTickets(100L, 42L, 1) } returns BuyOutcome.UnknownUser

        modal.handle(ctx, 0)

        assertTrue(messageSlot.captured.contains("No user record"))
    }
}
