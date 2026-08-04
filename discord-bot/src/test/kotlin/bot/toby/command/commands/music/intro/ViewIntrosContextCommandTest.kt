package bot.toby.command.commands.music.intro

import bot.toby.button.buttons.music.PlayIntroButton
import bot.toby.helpers.IntroHelper
import bot.toby.menu.menus.CopyIntroMenu
import database.dto.music.MusicDto
import database.dto.user.UserDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
import net.dv8tion.jda.api.events.interaction.command.UserContextInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Right-click a member → **Apps → View intros**.
 *
 * One entry rather than three, because Discord allows five user context
 * commands per application in total — so the list carries the actions.
 */
class ViewIntrosContextCommandTest {

    private val guildId = 7L
    private val callerId = 1L
    private val targetId = 99L

    private lateinit var introHelper: IntroHelper
    private lateinit var command: ViewIntrosContextCommand
    private lateinit var event: UserContextInteractionEvent
    private lateinit var hook: InteractionHook
    private lateinit var send: WebhookMessageCreateAction<Message>
    private lateinit var targetMember: Member

    private val embeds = mutableListOf<MessageEmbed>()
    private val rows = mutableListOf<List<ActionRow>>()
    private val replies = mutableListOf<String>()
    private val caller = UserDto(discordId = callerId, guildId = guildId)

    @BeforeEach
    fun setUp() {
        introHelper = mockk(relaxed = true)
        command = ViewIntrosContextCommand(introHelper)

        val guild = mockk<Guild>(relaxed = true) {
            every { idLong } returns guildId
            every { id } returns guildId.toString()
        }
        targetMember = mockk(relaxed = true) {
            every { idLong } returns targetId
            every { effectiveName } returns "Them"
            every { effectiveAvatarUrl } returns "https://cdn.example/them.png"
            every { this@mockk.guild } returns guild
        }
        hook = mockk(relaxed = true)
        send = mockk(relaxed = true)
        event = mockk(relaxed = true) {
            every { this@mockk.guild } returns guild
            every { this@mockk.hook } returns this@ViewIntrosContextCommandTest.hook
            every { this@mockk.targetMember } returns this@ViewIntrosContextCommandTest.targetMember
            every { user } returns mockk<User>(relaxed = true) { every { idLong } returns callerId }
            every { target } returns mockk<User>(relaxed = true) { every { effectiveName } returns "Them" }
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

    private fun theirIntros(count: Int, enabled: Boolean = true): UserDto =
        UserDto(discordId = targetId, guildId = guildId).apply {
            musicDtos = (1..count).map {
                MusicDto(this, it, "track$it", 80, byteArrayOf(1)).apply { this.enabled = enabled }
            }.toMutableList()
        }

    private fun components() = rows.flatten().flatMap { it.components }

    @Test
    fun `it shows the target's intros to anyone who asks`() {
        // Same reasoning as opening up `/listintros user:` — an intro plays
        // out loud to the whole channel on every join, so there is nothing
        // here that looking could reveal.
        every { introHelper.findUserById(targetId, guildId) } returns theirIntros(2)

        command.handle(event, caller, 5)

        assertEquals(2, embeds.single().fields.size)
        assertTrue(embeds.single().title!!.contains("Them"))
    }

    @Test
    fun `the list comes with a play button and a copy menu`() {
        every { introHelper.findUserById(targetId, guildId) } returns theirIntros(2)

        command.handle(event, caller, 5)

        val ids = components().mapNotNull {
            (it as? Button)?.customId ?: (it as? StringSelectMenu)?.customId
        }
        assertTrue(ids.any { it.startsWith(PlayIntroButton.BUTTON_NAME) }, ids.toString())
        assertTrue(ids.contains(CopyIntroMenu.MENU_NAME), ids.toString())
    }

    @Test
    fun `the play button carries the target's id, since the message is ephemeral`() {
        // Nothing else survives to say whose intro the button was for.
        every { introHelper.findUserById(targetId, guildId) } returns theirIntros(1)

        command.handle(event, caller, 5)

        val play = components().filterIsInstance<Button>()
            .single { it.customId?.startsWith("playintro") == true }
        assertEquals("playintro:$targetId", play.customId)
    }

    @Test
    fun `the copy menu lists every one of their intros`() {
        every { introHelper.findUserById(targetId, guildId) } returns theirIntros(3)

        command.handle(event, caller, 5)

        val menu = components().filterIsInstance<StringSelectMenu>().single()
        assertEquals(3, menu.options.size)
        assertEquals(listOf("7_99_1", "7_99_2", "7_99_3"), menu.options.map { it.value })
    }

    @Test
    fun `looking at your own intros offers no copy menu`() {
        // Copying your own would only ever duplicate a row you already have.
        every { targetMember.idLong } returns callerId
        caller.musicDtos = mutableListOf(MusicDto(caller, 1, "mine", 90, byteArrayOf(1)))
        every { targetMember.effectiveName } returns "Me"

        command.handle(event, caller, 5)

        assertTrue(components().filterIsInstance<StringSelectMenu>().isEmpty())
        // ...but you can still hear it.
        assertTrue(components().filterIsInstance<Button>().any { it.customId?.startsWith("playintro") == true })
    }

    @Test
    fun `your own list is read from the dto already in hand`() {
        every { targetMember.idLong } returns callerId

        command.handle(event, caller, 5)

        verify(exactly = 0) { introHelper.findUserById(any(), any()) }
    }

    @Test
    fun `a member with nothing set gets the empty state and no actions`() {
        every { introHelper.findUserById(targetId, guildId) } returns theirIntros(0)

        command.handle(event, caller, 5)

        assertTrue(embeds.single().description!!.contains("/setintro"))
        assertTrue(components().filterIsInstance<StringSelectMenu>().isEmpty())
        assertTrue(components().filterIsInstance<Button>().none { it.customId?.startsWith("playintro") == true })
    }

    @Test
    fun `a member whose intros are all switched off is not offered a play button`() {
        // Nothing would come out, so offering to play it is a lie.
        every { introHelper.findUserById(targetId, guildId) } returns theirIntros(2, enabled = false)

        command.handle(event, caller, 5)

        assertTrue(components().filterIsInstance<Button>().none { it.customId?.startsWith("playintro") == true })
        // Copying a switched-off intro is still fine — it comes across enabled.
        assertTrue(components().filterIsInstance<StringSelectMenu>().isNotEmpty())
    }

    @Test
    fun `the web dashboard link is always offered`() {
        every { introHelper.findUserById(targetId, guildId) } returns theirIntros(0)

        command.handle(event, caller, 5)

        assertTrue(components().filterIsInstance<Button>().any { it.url != null })
    }

    @Test
    fun `someone who has left the server is reported rather than looked up`() {
        every { event.targetMember } returns null

        command.handle(event, caller, 5)

        verify(exactly = 0) { introHelper.findUserById(any(), any()) }
        assertTrue(replies.single().contains("isn't a member of this server"), replies.single())
    }

    @Test
    fun `it registers as a user context entry`() {
        assertEquals("View intros", command.name)
        assertEquals(
            net.dv8tion.jda.api.interactions.commands.Command.Type.USER,
            command.commandData.type,
        )
    }

    @Test
    fun `the entry name fits Discord's cap`() {
        assertTrue(ViewIntrosContextCommand.COMMAND_NAME.length <= 32)
    }

    @Test
    fun `the components fit Discord's five-row cap`() {
        every { introHelper.findUserById(targetId, guildId) } returns theirIntros(3)

        command.handle(event, caller, 5)

        val captured = slot<List<ActionRow>>()
        verify { send.addComponents(capture(captured)) }
        assertTrue(captured.captured.size <= 5)
    }
}
