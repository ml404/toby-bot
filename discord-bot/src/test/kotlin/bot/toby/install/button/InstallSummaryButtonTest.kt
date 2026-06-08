package bot.toby.install.button

import bot.toby.install.InstallWizard
import database.service.economy.JackpotService
import database.service.guild.ConfigService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class InstallSummaryButtonTest {

    private lateinit var configService: ConfigService
    private lateinit var jackpotService: JackpotService
    private lateinit var button: InstallSummaryButton
    private lateinit var fx: InstallButtonFixture

    @BeforeEach
    fun setUp() {
        configService = mockk(relaxed = true)
        jackpotService = mockk(relaxed = true)
        button = InstallSummaryButton(configService, jackpotService)
        fx = InstallButtonFixture()
    }

    @Test
    fun `name matches the view-setup id`() {
        assertEquals(InstallWizard.BTN_VIEW_SETUP, button.name)
    }

    @Test
    fun `defersReply is false like the other install buttons`() {
        assertEquals(false, button.defersReply)
    }

    @Test
    fun `non-owner is rejected with no summary reply`() {
        fx.asNonOwner()

        button.handle(fx.ctx, mockk(relaxed = true), 0)

        verify(exactly = 1) { fx.event.reply(any<String>()) }
        verify(exactly = 0) { fx.event.replyEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `owner gets an ephemeral setup summary`() {
        val reply: ReplyCallbackAction = mockk(relaxed = true)
        every { fx.event.replyEmbeds(any<MessageEmbed>()) } returns reply
        every { reply.setEphemeral(any()) } returns reply

        button.handle(fx.ctx, mockk(relaxed = true), 0)

        verify(exactly = 1) { fx.event.replyEmbeds(any<MessageEmbed>()) }
        verify(exactly = 1) { reply.setEphemeral(true) }
    }
}
