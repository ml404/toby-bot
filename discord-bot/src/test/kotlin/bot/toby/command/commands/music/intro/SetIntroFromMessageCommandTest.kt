package bot.toby.command.commands.music.intro

import bot.toby.helpers.IntroHelper
import bot.toby.helpers.PendingIntro
import bot.toby.modal.modals.SetIntroFromMessageModal
import database.dto.music.MusicDto
import database.dto.user.UserDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.textinput.TextInput
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import net.dv8tion.jda.api.modals.Modal as JdaModal

class SetIntroFromMessageCommandTest {

    private lateinit var introHelper: IntroHelper
    private lateinit var command: SetIntroFromMessageCommand
    private lateinit var event: MessageContextInteractionEvent
    private lateinit var hook: InteractionHook
    private lateinit var target: Message
    private val replies = mutableListOf<String>()
    private val pending = mutableMapOf<Pair<Long, Long>, PendingIntro>()

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
        // Back the helper's pending-intro API with a local map so the tests
        // can assert on what was parked without reaching into its cache.
        every { introHelper.parkPendingIntro(any(), any(), any()) } answers {
            pending[firstArg<Long>() to secondArg<Long>()] = thirdArg()
        }
        every { introHelper.pendingIntro(any(), any()) } answers {
            pending[firstArg<Long>() to secondArg<Long>()]
        }
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
    fun `an mp3 attachment opens the clip form with the file parked for it`() {
        val file = mp3()
        withMessage(attachments = listOf(file))

        command.handle(event, userDto, 5)

        // Nothing is saved from the command any more — the modal validates and
        // persists, which is how this entry point finally gets clip bounds and
        // the duration check that lives with them.
        verify(exactly = 0) { introHelper.handleAttachment(any(), any(), any(), any(), any(), any()) }
        verify { event.replyModal(any()) }
        assertEquals(file, pending[7L to 1L]?.attachment)
        assertNull(pending[7L to 1L]?.url)
        assertEquals(80, pending[7L to 1L]?.volume)
    }

    @Test
    fun `a link in the message body is parked for the clip form`() {
        withMessage(content = "this one https://www.youtube.com/watch?v=abc is unreal")

        command.handle(event, userDto, 5)

        verify { event.replyModal(any()) }
        assertEquals("https://www.youtube.com/watch?v=abc", pending[7L to 1L]?.url)
        assertNull(pending[7L to 1L]?.attachment)
    }

    @Test
    fun `an attachment wins over a link in the same message`() {
        val file = mp3()
        withMessage(attachments = listOf(file), content = "or maybe https://youtu.be/abc")

        command.handle(event, userDto, 5)

        assertEquals(file, pending[7L to 1L]?.attachment)
        assertNull(pending[7L to 1L]?.url)
    }

    @Test
    fun `a non-audio attachment is ignored in favour of a link`() {
        withMessage(attachments = listOf(nonAudio()), content = "https://youtu.be/abc")

        command.handle(event, userDto, 5)

        assertEquals("https://youtu.be/abc", pending[7L to 1L]?.url)
        assertNull(pending[7L to 1L]?.attachment)
    }

    @Test
    fun `a message with nothing usable explains itself without opening a form`() {
        withMessage(attachments = listOf(nonAudio()), content = "lol")

        command.handle(event, userDto, 5)

        // The refusal goes out via event.reply, a JDA default interface method
        // that mockk can't intercept on these mocks, so the assertion is on the
        // observable contract: no form, and nothing parked to save later.
        verify(exactly = 0) { event.replyModal(any()) }
        assertNull(pending[7L to 1L])
    }

    @Test
    fun `the form title names the source that was picked`() {
        withMessage(attachments = listOf(mp3("banger.mp3")))
        val modal = slot<JdaModal>()
        every { event.replyModal(capture(modal)) } returns mockk(relaxed = true)

        command.handle(event, userDto, 5)

        assertTrue(modal.captured.title.contains("banger.mp3"), modal.captured.title)
    }

    @Test
    fun `the form offers volume and both clip bounds`() {
        withMessage(attachments = listOf(mp3()))
        val modal = slot<JdaModal>()
        every { event.replyModal(capture(modal)) } returns mockk(relaxed = true)

        command.handle(event, userDto, 5)

        val fieldIds = modal.captured.components
            .mapNotNull { (it as? Label)?.child as? TextInput }
            .map { it.customId }
        assertEquals(
            listOf(
                SetIntroFromMessageModal.FIELD_VOLUME,
                SetIntroFromMessageModal.FIELD_START,
                SetIntroFromMessageModal.FIELD_END,
            ),
            fieldIds,
        )
    }

    @Test
    fun `the command does not defer, so the form can open at all`() {
        // A modal has to be an interaction's first response; deferring first
        // makes replyModal impossible.
        assertFalse(command.defersReply)
    }

    @Test
    fun `the command name fits Discord's context menu limit`() {
        assertTrue(command.name.length <= 32, "context menu names are capped at 32 characters")
        assertEquals("Set as my intro", command.name)
    }
}
