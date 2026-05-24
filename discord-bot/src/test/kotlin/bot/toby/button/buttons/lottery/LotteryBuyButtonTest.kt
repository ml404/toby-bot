package bot.toby.button.buttons.lottery

import bot.toby.button.ButtonTest
import bot.toby.button.ButtonTest.Companion.event
import bot.toby.button.DefaultButtonContext
import database.dto.UserDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.modals.Modal
import net.dv8tion.jda.api.requests.restaction.interactions.ModalCallbackAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import bot.toby.button.buttons.lottery.LotteryBuyButton

class LotteryBuyButtonTest : ButtonTest {

    private lateinit var button: LotteryBuyButton

    @BeforeEach
    override fun setup() {
        super.setup()
        button = LotteryBuyButton()
    }

    @AfterEach
    override fun tearDown() {
        super.tearDown()
        unmockkAll()
    }

    @Test
    fun `name is lottery_buy`() {
        assertEquals("lottery_buy", button.name)
    }

    @Test
    fun `defersReply is false`() {
        assertFalse(button.defersReply)
    }

    @Test
    fun `handle opens a modal with id lottery_buy`() {
        val modalSlot = slot<Modal>()
        val modalAction = mockk<ModalCallbackAction>(relaxed = true)
        every { event.replyModal(capture(modalSlot)) } returns modalAction

        button.handle(DefaultButtonContext(event), UserDto(1L, 1L), 0)

        verify(exactly = 1) { event.replyModal(any<Modal>()) }
        verify(exactly = 1) { modalAction.queue() }
        assertEquals("lottery_buy", modalSlot.captured.id)
        assertEquals("Buy Lottery Tickets", modalSlot.captured.title)
    }
}
