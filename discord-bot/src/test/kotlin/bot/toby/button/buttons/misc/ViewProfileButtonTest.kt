package bot.toby.button.buttons.misc

import bot.toby.button.buttons.economy.TipUserButton
import bot.toby.button.buttons.music.ViewIntrosButton
import bot.toby.helpers.ProfileCardHelper
import core.button.ButtonContext
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
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import net.dv8tion.jda.api.utils.FileUpload
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The looking-up half of the cross-link — intros → profile.
 *
 * Somebody's intro playing is the most common reason to wonder who they are,
 * and the answer used to mean closing the list and retyping their name into
 * `/profile`.
 */
class ViewProfileButtonTest {

    private val guildId = 7L
    private val callerId = 1L
    private val targetId = 99L

    private lateinit var profileCardHelper: ProfileCardHelper
    private lateinit var button: ViewProfileButton
    private lateinit var ctx: ButtonContext
    private lateinit var event: ButtonInteractionEvent
    private lateinit var guild: Guild
    private lateinit var targetMember: Member
    private lateinit var send: WebhookMessageCreateAction<Message>

    private val embeds = mutableListOf<MessageEmbed>()
    private val rows = mutableListOf<Collection<ActionRow>>()
    private val files = mutableListOf<FileUpload>()
    private val replies = mutableListOf<String>()
    private val caller = UserDto(discordId = callerId, guildId = guildId)

    @BeforeEach
    fun setUp() {
        profileCardHelper = mockk(relaxed = true)
        button = ViewProfileButton(profileCardHelper)

        guild = mockk(relaxed = true) {
            every { idLong } returns guildId
            every { id } returns guildId.toString()
        }
        targetMember = mockk(relaxed = true) {
            every { idLong } returns targetId
            every { effectiveName } returns "Them"
            every { this@mockk.guild } returns this@ViewProfileButtonTest.guild
            every { user } returns mockk<User>(relaxed = true) { every { isBot } returns false }
        }
        every { guild.getMemberById(targetId) } returns targetMember

        val hook = mockk<InteractionHook>(relaxed = true)
        send = mockk(relaxed = true)
        event = mockk(relaxed = true) {
            every { componentId } returns "viewprofile:$targetId"
            every { this@mockk.hook } returns hook
            every { user } returns mockk<User>(relaxed = true) { every { idLong } returns callerId }
        }
        ctx = mockk(relaxed = true) {
            every { this@mockk.event } returns this@ViewProfileButtonTest.event
            every { this@mockk.guild } returns this@ViewProfileButtonTest.guild
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
    fun `it posts the named member's card`() {
        button.handle(ctx, caller, 5)

        verify(exactly = 1) { profileCardHelper.renderPng(guild, targetMember) }
        assertEquals(1, files.size)
        assertTrue(embeds.single().image!!.url!!.contains("profile.png"))
    }

    @Test
    fun `the card it posts leads back to their intros`() {
        // Pressing back and forth is fine — each side is an ephemeral of its
        // own, so neither replaces what you were looking at.
        button.handle(ctx, caller, 5)

        assertTrue(buttons().any { it.customId == "${ViewIntrosButton.BUTTON_NAME}:$targetId" })
    }

    @Test
    fun `arriving from somebody else's intro list offers to tip them`() {
        button.handle(ctx, caller, 5)

        assertTrue(buttons().any { it.customId == "${TipUserButton.BUTTON_NAME}:$targetId" })
    }

    @Test
    fun `arriving from your own list does not offer to tip yourself`() {
        every { event.componentId } returns "viewprofile:$callerId"
        every { guild.getMemberById(callerId) } returns targetMember
        every { targetMember.idLong } returns callerId

        button.handle(ctx, caller, 5)

        assertTrue(buttons().none { it.customId?.startsWith(TipUserButton.BUTTON_NAME) == true })
    }

    @Test
    fun `a failed render says so rather than leaving a gap`() {
        every { profileCardHelper.renderPng(any(), any()) } returns null

        button.handle(ctx, caller, 5)

        assertTrue(replies.single().contains("couldn't render"), replies.single())
        assertTrue(files.isEmpty())
    }

    @Test
    fun `a member who has left since the list was posted is reported`() {
        every { guild.getMemberById(targetId) } returns null

        button.handle(ctx, caller, 5)

        verify(exactly = 0) { profileCardHelper.renderPng(any(), any()) }
        assertTrue(replies.single().contains("any more"), replies.single())
    }

    @Test
    fun `an id that arrives mangled is refused rather than guessed at`() {
        every { event.componentId } returns "viewprofile:not-a-snowflake"

        button.handle(ctx, caller, 5)

        verify(exactly = 0) { profileCardHelper.renderPng(any(), any()) }
        assertTrue(replies.single().contains("lost track"), replies.single())
    }

    @Test
    fun `the button carries the member it is for`() {
        assertEquals("${ViewProfileButton.BUTTON_NAME}:$targetId", ViewProfileButton.button(targetMember).customId)
    }
}
