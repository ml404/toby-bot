package bot.toby.command.commands.music.intro

import bot.toby.helpers.IntroHelper
import bot.toby.helpers.PendingIntro
import database.dto.music.MusicDto
import database.dto.user.UserDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.Message.Attachment
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI

class SetIntroFromMessageCommandTest {

    private lateinit var introHelper: IntroHelper
    private lateinit var command: SetIntroFromMessageCommand
    private lateinit var event: MessageContextInteractionEvent
    private lateinit var hook: InteractionHook
    private lateinit var target: Message
    private val replies = mutableListOf<String>()
    private val pending = mutableMapOf<Long, PendingIntro?>()

    private val userDto = UserDto(discordId = 1L, guildId = 7L)

    @BeforeEach
    fun setUp() {
        introHelper = mockk(relaxed = true)
        command = SetIntroFromMessageCommand(introHelper)

        hook = mockk(relaxed = true)
        target = mockk(relaxed = true)
        val guild = mockk<Guild>(relaxed = true) {
            every { idLong } returns 7L
            every { id } returns "7"
        }
        val member = mockk<Member>(relaxed = true) { every { effectiveName } returns "Joiner" }
        event = mockk(relaxed = true) {
            every { this@mockk.hook } returns this@SetIntroFromMessageCommandTest.hook
            every { this@mockk.guild } returns guild
            every { this@mockk.member } returns member
            every { this@mockk.target } returns this@SetIntroFromMessageCommandTest.target
            every { user } returns mockk<User>(relaxed = true) { every { effectiveName } returns "Joiner" }
        }

        replies.clear()
        pending.clear()
        val send = mockk<WebhookMessageCreateAction<Message>>(relaxed = true)
        every { hook.sendMessage(capture(replies)) } returns send
        every { send.setEphemeral(true) } returns send

        every { introHelper.defaultIntroVolume(any()) } returns 80
        every { introHelper.pendingIntros } returns pending
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun mp3(name: String = "banger.mp3"): Attachment = mockk(relaxed = true) {
        every { fileExtension } returns "mp3"
        every { fileName } returns name
    }

    private fun nonAudio(): Attachment = mockk(relaxed = true) {
        every { fileExtension } returns "png"
        every { fileName } returns "meme.png"
    }

    private fun withMessage(attachments: List<Attachment> = emptyList(), content: String = "") {
        every { target.attachments } returns attachments
        every { target.contentRaw } returns content
    }

    @Test
    fun `an mp3 attachment becomes the intro`() {
        val file = mp3()
        withMessage(attachments = listOf(file))

        command.handle(event, userDto, 5)

        verify { introHelper.handleAttachment(event, userDto, "Joiner", 5, file, 80) }
    }

    @Test
    fun `a link in the message body becomes the intro`() {
        withMessage(content = "this one https://www.youtube.com/watch?v=abc is unreal")

        command.handle(event, userDto, 5)

        val uri = slot<URI>()
        verify { introHelper.handleUrl(event, userDto, "Joiner", 5, capture(uri), 80) }
        assertEquals("https://www.youtube.com/watch?v=abc", uri.captured.toString())
    }

    @Test
    fun `an attachment wins over a link in the same message`() {
        val file = mp3()
        withMessage(attachments = listOf(file), content = "or maybe https://youtu.be/abc")

        command.handle(event, userDto, 5)

        verify { introHelper.handleAttachment(event, userDto, "Joiner", 5, file, 80) }
        verify(exactly = 0) { introHelper.handleUrl(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a non-audio attachment is ignored in favour of a link`() {
        withMessage(attachments = listOf(nonAudio()), content = "https://youtu.be/abc")

        command.handle(event, userDto, 5)

        verify { introHelper.handleUrl(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a message with nothing usable explains itself`() {
        withMessage(attachments = listOf(nonAudio()), content = "lol")

        command.handle(event, userDto, 5)

        verify(exactly = 0) { introHelper.handleAttachment(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { introHelper.handleUrl(any(), any(), any(), any(), any(), any()) }
        assertTrue(replies.single().contains("no MP3 attachment or link"))
    }

    @Test
    fun `at the slot limit the user is asked what to replace`() {
        val embeds = mutableListOf<MessageEmbed>()
        val embedSend = mockk<WebhookMessageCreateAction<Message>>(relaxed = true)
        every { hook.sendMessageEmbeds(capture(embeds)) } returns embedSend
        every { embedSend.addContent(any()) } returns embedSend
        // Keep the builder fluent: JDA's covariant addComponents bridge
        // ClassCastExceptions if a relaxed child answers it.
        every { embedSend.addComponents(any<ActionRow>()) } returns embedSend
        every { embedSend.setEphemeral(true) } returns embedSend

        val full = UserDto(discordId = 1L, guildId = 7L).apply {
            musicDtos = (1..3).map { MusicDto(this, it, "existing$it", 90, byteArrayOf(1)) }.toMutableList()
        }
        val file = mp3()
        withMessage(attachments = listOf(file))

        command.handle(event, full, 5)

        // Nothing saved yet — the pick is parked for SetIntroMenu to resolve.
        verify(exactly = 0) { introHelper.handleAttachment(any(), any(), any(), any(), any(), any()) }
        assertEquals(file, pending[1L]?.attachment)
        assertEquals(80, pending[1L]?.volume)
        assertNull(pending[1L]?.url)
        assertEquals(3, embeds.single().fields.size)
    }

    @Test
    fun `the command name fits Discord's context menu limit`() {
        assertTrue(command.name.length <= 32, "context menu names are capped at 32 characters")
        assertEquals("Set as my intro", command.name)
    }
}
