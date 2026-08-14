package bot.toby.command.commands.misc

import bot.toby.button.buttons.economy.TipUserButton
import bot.toby.button.buttons.music.ViewIntrosButton
import bot.toby.helpers.ProfileCardHelper
import database.dto.user.UserDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.interaction.command.UserContextInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import net.dv8tion.jda.api.utils.FileUpload
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Right-click a member → **Apps → Profile**.
 *
 * The card itself is a picture and so a dead end; what makes the entry worth a
 * slot is the pair of ways off it.
 */
class ProfileContextCommandTest {

    private val guildId = 7L
    private val callerId = 1L
    private val targetId = 99L

    private lateinit var profileCardHelper: ProfileCardHelper
    private lateinit var command: ProfileContextCommand
    private lateinit var event: UserContextInteractionEvent
    private lateinit var send: WebhookMessageCreateAction<Message>
    private lateinit var targetMember: Member
    private lateinit var guild: Guild

    private val embeds = mutableListOf<MessageEmbed>()
    private val rows = mutableListOf<Collection<ActionRow>>()
    private val files = mutableListOf<FileUpload>()
    private val replies = mutableListOf<String>()
    private val caller = UserDto(discordId = callerId, guildId = guildId)

    @BeforeEach
    fun setUp() {
        profileCardHelper = mockk(relaxed = true)
        command = ProfileContextCommand(profileCardHelper)

        guild = mockk(relaxed = true) {
            every { idLong } returns guildId
            every { id } returns guildId.toString()
        }
        targetMember = mockk(relaxed = true) {
            every { idLong } returns targetId
            every { effectiveName } returns "Them"
            every { this@mockk.guild } returns this@ProfileContextCommandTest.guild
            every { user } returns mockk<User>(relaxed = true) { every { isBot } returns false }
        }
        val hook = mockk<InteractionHook>(relaxed = true)
        send = mockk(relaxed = true)
        event = mockk(relaxed = true) {
            every { this@mockk.guild } returns this@ProfileContextCommandTest.guild
            every { this@mockk.hook } returns hook
            every { this@mockk.targetMember } returns this@ProfileContextCommandTest.targetMember
            every { user } returns mockk<User>(relaxed = true) { every { idLong } returns callerId }
            every { target } returns mockk<User>(relaxed = true) { every { effectiveName } returns "Them" }
        }

        embeds.clear()
        rows.clear()
        files.clear()
        replies.clear()
        every { hook.sendMessageEmbeds(capture(embeds)) } returns send
        every { hook.sendMessage(capture(replies)) } returns send
        every { send.addFiles(capture(files)) } returns send
        every { send.addComponents(capture(rows)) } returns send
        every { send.setEphemeral(any()) } returns send

        every { profileCardHelper.renderPng(guild, targetMember) } returns ByteArray(2048)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun buttons() = rows.flatten().flatMap { it.components }.filterIsInstance<Button>()

    @Test
    fun `it posts the card as an attached PNG`() {
        command.handle(event, caller, 5)

        assertEquals(1, files.size)
        assertTrue(embeds.single().image!!.url!!.contains("profile.png"), embeds.single().image!!.url!!)
    }

    @Test
    fun `the card leads on to their intros`() {
        command.handle(event, caller, 5)

        assertTrue(
            buttons().any { it.customId == "${ViewIntrosButton.BUTTON_NAME}:$targetId" },
            buttons().map { it.customId }.toString(),
        )
    }

    @Test
    fun `the card leads on to tipping them`() {
        command.handle(event, caller, 5)

        assertTrue(
            buttons().any { it.customId == "${TipUserButton.BUTTON_NAME}:$targetId" },
            buttons().map { it.customId }.toString(),
        )
    }

    @Test
    fun `your own card does not offer to tip yourself`() {
        every { targetMember.idLong } returns callerId
        every { profileCardHelper.renderPng(guild, targetMember) } returns ByteArray(8)

        command.handle(event, caller, 5)

        assertTrue(buttons().none { it.customId?.startsWith(TipUserButton.BUTTON_NAME) == true })
        assertTrue(buttons().any { it.customId?.startsWith(ViewIntrosButton.BUTTON_NAME) == true })
    }

    @Test
    fun `a bot's card does not offer to tip it`() {
        // Bots hold no credit, so the tip would bounce off TipService.
        every { targetMember.user } returns mockk(relaxed = true) { every { isBot } returns true }

        command.handle(event, caller, 5)

        assertTrue(buttons().none { it.customId?.startsWith(TipUserButton.BUTTON_NAME) == true })
    }

    @Test
    fun `it stays ephemeral, unlike the slash command`() {
        // A slash command is something you chose to say out loud; a right-click
        // is a glance, and it shouldn't put somebody else's card in the channel.
        command.handle(event, caller, 5)

        verify { send.setEphemeral(true) }
    }

    @Test
    fun `a failed render says so rather than leaving a gap`() {
        every { profileCardHelper.renderPng(any(), any()) } returns null

        command.handle(event, caller, 5)

        assertTrue(replies.single().contains("couldn't render"), replies.single())
        assertTrue(files.isEmpty())
    }

    @Test
    fun `someone who has left the server is reported rather than rendered`() {
        every { event.targetMember } returns null

        command.handle(event, caller, 5)

        verify(exactly = 0) { profileCardHelper.renderPng(any(), any()) }
        assertTrue(replies.single().contains("isn't a member of this server"), replies.single())
    }

    @Test
    fun `it registers as a user context entry`() {
        assertEquals("Profile", command.name)
        assertEquals(
            net.dv8tion.jda.api.interactions.commands.Command.Type.USER,
            command.commandData.type,
        )
    }

    @Test
    fun `the entry name fits Discord's cap`() {
        assertTrue(ProfileContextCommand.COMMAND_NAME.length <= 32)
    }
}
