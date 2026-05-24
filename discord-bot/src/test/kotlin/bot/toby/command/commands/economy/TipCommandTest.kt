package bot.toby.command.commands.economy

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.CommandTest.Companion.replyCallbackAction
import bot.toby.command.DefaultCommandContext
import bot.toby.modal.modals.TipMessageModal
import database.dto.user.UserDto
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.modals.Modal
import net.dv8tion.jda.api.requests.restaction.interactions.ModalCallbackAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class TipCommandTest : CommandTest {
    private lateinit var command: TipCommand
    private val modalCallback: ModalCallbackAction = mockk(relaxed = true)

    private val senderId = 1L
    private val recipientId = 2L
    private val guildId = 42L

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        command = TipCommand()
        every { guild.idLong } returns guildId
        every { event.replyModal(any<Modal>()) } returns modalCallback
        every { modalCallback.queue() } just runs
        // CommandTest's shared mock has `event.reply(any<String>())` set to
        // `just awaits` (suspends forever). TipCommand uses `event.reply(...)`
        // for early-out validation — it can't `deferReply()` first because the
        // happy path opens a modal, and modals can't follow a defer. Override
        // the hang-stub with the working reply chain.
        every { event.reply(any<String>()) } returns replyCallbackAction
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearAllMocks()
    }

    private fun userOpt(target: User): OptionMapping {
        val o = mockk<OptionMapping>(relaxed = true)
        every { o.asUser } returns target
        return o
    }

    private fun intOpt(value: Long): OptionMapping {
        val o = mockk<OptionMapping>(relaxed = true)
        every { o.asLong } returns value
        return o
    }

    private fun targetUser(idLong: Long, isBot: Boolean = false): User {
        val u = mockk<User>(relaxed = true)
        every { u.idLong } returns idLong
        every { u.isBot } returns isBot
        return u
    }

    @Test
    fun `opens tip-message modal with recipient and amount encoded in id`() {
        val sender = UserDto(discordId = senderId, guildId = guildId).apply { socialCredit = 200L }
        val target = targetUser(recipientId)
        every { event.getOption("user") } returns userOpt(target)
        every { event.getOption("amount") } returns intOpt(50L)
        val captured = slot<Modal>()
        every { event.replyModal(capture(captured)) } returns modalCallback

        command.handle(DefaultCommandContext(event), sender, 5)

        verify { event.replyModal(any<Modal>()) }
        assertEquals(TipMessageModal.customId(recipientId, 50L), captured.captured.id)
        // No service call — TipMessageModal owns execution.
    }

    @Test
    fun `rejects bot recipient before opening modal`() {
        val sender = UserDto(discordId = senderId, guildId = guildId).apply { socialCredit = 200L }
        val bot = targetUser(recipientId, isBot = true)
        every { event.getOption("user") } returns userOpt(bot)
        every { event.getOption("amount") } returns intOpt(50L)

        command.handle(DefaultCommandContext(event), sender, 5)

        verify(exactly = 0) { event.replyModal(any<Modal>()) }
        // No service call — TipMessageModal owns execution.
    }

    @Test
    fun `rejects self recipient before opening modal`() {
        val sender = UserDto(discordId = senderId, guildId = guildId).apply { socialCredit = 200L }
        val self = targetUser(senderId)
        every { event.getOption("user") } returns userOpt(self)
        every { event.getOption("amount") } returns intOpt(50L)

        command.handle(DefaultCommandContext(event), sender, 5)

        verify(exactly = 0) { event.replyModal(any<Modal>()) }
        // No service call — TipMessageModal owns execution.
    }

    @Test
    fun `customId round-trips through TipMessageModal helper`() {
        val id = TipMessageModal.customId(123L, 250L)
        assertTrue(id.startsWith("${TipMessageModal.MODAL_NAME}:"))
        val parts = id.split(':')
        assertEquals(123L, parts[1].toLong())
        assertEquals(250L, parts[2].toLong())
    }
}
