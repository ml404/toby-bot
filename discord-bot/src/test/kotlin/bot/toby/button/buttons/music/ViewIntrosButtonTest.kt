package bot.toby.button.buttons.music

import bot.toby.helpers.IntroHelper
import bot.toby.menu.menus.CopyIntroMenu
import core.button.ButtonContext
import database.dto.music.MusicDto
import database.dto.user.UserDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.selections.StringSelectMenu
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The listening half of the profile card — profile → intros.
 *
 * Renders the same message the **View intros** right-click entry does, via the
 * shared [bot.toby.intro.IntroListView], so what is worth covering here is the
 * lookup either side of it.
 */
class ViewIntrosButtonTest {

    private val guildId = 7L
    private val callerId = 1L
    private val targetId = 99L

    private lateinit var introHelper: IntroHelper
    private lateinit var button: ViewIntrosButton
    private lateinit var ctx: ButtonContext
    private lateinit var event: ButtonInteractionEvent
    private lateinit var guild: Guild
    private lateinit var send: WebhookMessageCreateAction<Message>

    private val embeds = mutableListOf<MessageEmbed>()
    private val rows = mutableListOf<Collection<ActionRow>>()
    private val replies = mutableListOf<String>()
    private val caller = UserDto(discordId = callerId, guildId = guildId)

    @BeforeEach
    fun setUp() {
        introHelper = mockk(relaxed = true)
        button = ViewIntrosButton(introHelper)

        guild = mockk(relaxed = true) {
            every { idLong } returns guildId
            every { id } returns guildId.toString()
        }
        val targetMember = mockk<Member>(relaxed = true) {
            every { idLong } returns targetId
            every { effectiveName } returns "Them"
            every { effectiveAvatarUrl } returns "https://cdn.example/them.png"
            every { this@mockk.guild } returns this@ViewIntrosButtonTest.guild
        }
        every { guild.getMemberById(targetId) } returns targetMember

        val hook = mockk<InteractionHook>(relaxed = true)
        send = mockk(relaxed = true)
        event = mockk(relaxed = true) {
            every { componentId } returns "viewintros:$targetId"
            every { this@mockk.hook } returns hook
            every { user } returns mockk<User>(relaxed = true) { every { idLong } returns callerId }
        }
        ctx = mockk(relaxed = true) {
            every { this@mockk.event } returns this@ViewIntrosButtonTest.event
            every { this@mockk.guild } returns this@ViewIntrosButtonTest.guild
        }

        embeds.clear()
        rows.clear()
        replies.clear()
        every { hook.sendMessageEmbeds(capture(embeds)) } returns send
        every { hook.sendMessage(capture(replies)) } returns send
        every { send.addComponents(capture(rows)) } returns send
        every { send.setEphemeral(any()) } returns send
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun theirIntros(count: Int): UserDto =
        UserDto(discordId = targetId, guildId = guildId).apply {
            musicDtos = (1..count).map { MusicDto(this, it, "track$it", 80, byteArrayOf(1)) }.toMutableList()
        }

    private fun components() = rows.flatten().flatMap { it.components }

    @Test
    fun `it shows the target's intros with a play button each`() {
        every { introHelper.findUserById(targetId, guildId) } returns theirIntros(2)

        button.handle(ctx, caller, 5)

        assertEquals(2, embeds.single().fields.size)
        assertEquals(
            listOf("playintro:7_99_1", "playintro:7_99_2"),
            components().filterIsInstance<Button>().mapNotNull { it.customId }
                .filter { it.startsWith("playintro") },
        )
    }

    @Test
    fun `looking at somebody else offers the copy menu`() {
        every { introHelper.findUserById(targetId, guildId) } returns theirIntros(2)

        button.handle(ctx, caller, 5)

        assertEquals(
            CopyIntroMenu.MENU_NAME,
            components().filterIsInstance<StringSelectMenu>().single().customId,
        )
    }

    @Test
    fun `your own list is read from the dto already in hand`() {
        every { event.componentId } returns "viewintros:$callerId"
        every { guild.getMemberById(callerId) } returns mockk(relaxed = true) {
            every { idLong } returns callerId
            every { this@mockk.guild } returns this@ViewIntrosButtonTest.guild
        }

        button.handle(ctx, caller, 5)

        verify(exactly = 0) { introHelper.findUserById(any(), any()) }
        assertTrue(components().filterIsInstance<StringSelectMenu>().isEmpty())
    }

    @Test
    fun `a member who has left since the card was posted is reported`() {
        every { guild.getMemberById(targetId) } returns null

        button.handle(ctx, caller, 5)

        verify(exactly = 0) { introHelper.findUserById(any(), any()) }
        assertTrue(replies.single().contains("any more"), replies.single())
    }

    @Test
    fun `an id that arrives mangled is refused rather than guessed at`() {
        every { event.componentId } returns "viewintros:not-a-snowflake"

        button.handle(ctx, caller, 5)

        verify(exactly = 0) { introHelper.findUserById(any(), any()) }
        assertTrue(replies.single().contains("lost track"), replies.single())
    }

    @Test
    fun `the button carries the member it is for`() {
        val target = mockk<Member>(relaxed = true) { every { idLong } returns targetId }

        assertEquals("${ViewIntrosButton.BUTTON_NAME}:$targetId", ViewIntrosButton.button(target).customId)
    }
}
