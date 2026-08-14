package bot.toby.command.commands.music.intro

import bot.toby.button.buttons.misc.ViewProfileButton
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
import net.dv8tion.jda.api.components.buttons.ButtonStyle
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
    fun `there is one play button per intro, each naming its own`() {
        // A single "play theirs" that then picked at random was a small
        // betrayal of the list right above it — with three slots you had a
        // one-in-three chance of hearing the one you were looking at.
        every { introHelper.findUserById(targetId, guildId) } returns theirIntros(3)

        command.handle(event, caller, 5)

        val play = components().filterIsInstance<Button>()
            .filter { it.customId?.startsWith("playintro") == true }
        assertEquals(3, play.size)
        assertEquals(
            listOf("playintro:7_99_1", "playintro:7_99_2", "playintro:7_99_3"),
            play.map { it.customId },
        )
        assertTrue(play.all { it.label.contains("track") }, play.map { it.label }.toString())
    }

    @Test
    fun `the buttons carry intro ids, since the message is ephemeral`() {
        // The id is guildId_discordId_slot, so it says everything the handler
        // needs — nothing else survives the interaction.
        every { introHelper.findUserById(targetId, guildId) } returns theirIntros(1)

        command.handle(event, caller, 5)

        val play = components().filterIsInstance<Button>()
            .single { it.customId?.startsWith("playintro") == true }
        assertEquals("playintro:7_99_1", play.customId)
    }

    @Test
    fun `playing and going-elsewhere are separate rows`() {
        // Three slots plus two links is exactly five: it fits today and breaks
        // silently the day a fourth slot is added, so they don't share a row.
        every { introHelper.findUserById(targetId, guildId) } returns theirIntros(3)

        command.handle(event, caller, 5)

        val first = rows.flatten().first().components
        assertEquals(3, first.size)
        assertTrue(first.all { (it as Button).customId?.startsWith("playintro") == true })
        assertTrue(rows.flatten().all { it.components.size <= 5 })
    }

    @Test
    fun `the list crosses over to their profile`() {
        // The other half of the cross-link: somebody's intro playing is the
        // most common reason to wonder who they are.
        every { introHelper.findUserById(targetId, guildId) } returns theirIntros(2)

        command.handle(event, caller, 5)

        assertTrue(
            components().filterIsInstance<Button>().any { it.customId == "${ViewProfileButton.BUTTON_NAME}:$targetId" },
            components().filterIsInstance<Button>().map { it.customId }.toString(),
        )
    }

    @Test
    fun `a member with no intros still gets the way out to their profile`() {
        // An empty list is exactly when the cross-link matters most — there is
        // nothing else on the message to do.
        every { introHelper.findUserById(targetId, guildId) } returns theirIntros(0)

        command.handle(event, caller, 5)

        assertTrue(
            components().filterIsInstance<Button>().any { it.customId == "${ViewProfileButton.BUTTON_NAME}:$targetId" },
        )
        // No empty play row: JDA rejects an action row with nothing in it.
        assertTrue(rows.flatten().all { it.components.isNotEmpty() })
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
    fun `an intro that sits out the rotation can still be played on purpose`() {
        // Switched off means "skip me on join", not "never playable" — and
        // hearing one is exactly how you answer "why doesn't this play?".
        // The embed above already marks it, so the button is offered quietly
        // rather than withheld.
        every { introHelper.findUserById(targetId, guildId) } returns theirIntros(2, enabled = false)

        command.handle(event, caller, 5)

        val play = components().filterIsInstance<Button>().filter { it.customId?.startsWith("playintro") == true }
        assertEquals(2, play.size)
        assertTrue(play.all { it.style == ButtonStyle.SECONDARY }, play.map { it.style }.toString())
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
