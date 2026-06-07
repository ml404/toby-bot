package bot.toby.install

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.CommandTest.Companion.member
import bot.toby.command.CommandTest.Companion.replyCallbackAction
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.DefaultCommandContext
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class InstallCommandTest : CommandTest {

    private lateinit var command: InstallCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        command = InstallCommand()
        every { event.reply(any<String>()) } returns replyCallbackAction
        every { event.replyEmbeds(any<MessageEmbed>()) } returns replyCallbackAction
        every { replyCallbackAction.addComponents(any<ActionRow>()) } returns replyCallbackAction
        every { guild.name } returns "Test Guild"
        every { member.isOwner } returns true
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearAllMocks()
    }

    @Test
    fun `name is install`() {
        assertEquals("install", command.name)
        assertTrue(command.subCommands.isEmpty(), "install has no subcommands")
    }

    @Test
    fun `non-owner gets ephemeral reject and no welcome posted`() {
        every { member.isOwner } returns false

        command.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 1) {
            event.reply(match<String> { it.contains("owner", ignoreCase = true) })
        }
        verify(exactly = 0) { event.replyEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `no-guild context replies ephemerally`() {
        every { event.guild } returns null

        command.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 1) {
            event.reply(match<String> { it.contains("server", ignoreCase = true) })
        }
        verify(exactly = 0) { event.replyEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `owner happy path posts welcome embed and the three wizard buttons`() {
        val embedSlot = slot<MessageEmbed>()
        val componentsSlot = slot<ActionRow>()
        every { event.replyEmbeds(capture(embedSlot)) } returns replyCallbackAction
        every { replyCallbackAction.addComponents(capture(componentsSlot)) } returns replyCallbackAction
        every { replyCallbackAction.queue() } just runs

        command.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 1) { event.replyEmbeds(any<MessageEmbed>()) }
        verify(exactly = 1) { replyCallbackAction.addComponents(any<ActionRow>()) }
        // Embed mentions the guild name.
        assertTrue(embedSlot.captured.description?.contains("Express") == true)
        // Action row has the three owner buttons plus the public help button.
        val buttons = componentsSlot.captured.components.filterIsInstance<Button>()
        assertEquals(4, buttons.size)
        assertEquals(InstallWizard.BTN_EXPRESS, buttons[0].customId)
        assertEquals(InstallWizard.BTN_CUSTOM, buttons[1].customId)
        assertEquals(InstallWizard.BTN_SKIP, buttons[2].customId)
        assertEquals(InstallWizard.BTN_HELP, buttons[3].customId)
    }
}
