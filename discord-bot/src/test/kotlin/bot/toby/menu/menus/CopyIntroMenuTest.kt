package bot.toby.menu.menus

import bot.toby.helpers.IntroHelper
import core.menu.MenuContext
import database.dto.music.MusicDto
import database.dto.user.UserDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CopyIntroMenuTest {

    private val guildId = 7L
    private val callerId = 1L

    private lateinit var introHelper: IntroHelper
    private lateinit var menu: CopyIntroMenu
    private lateinit var ctx: MenuContext
    private lateinit var event: StringSelectInteractionEvent

    private val replies = mutableListOf<String>()
    private val me = UserDto(discordId = callerId, guildId = guildId)
    private val theirs = UserDto(discordId = 99L, guildId = guildId)
    private lateinit var source: MusicDto

    @BeforeEach
    fun setUp() {
        introHelper = mockk(relaxed = true)
        menu = CopyIntroMenu(introHelper)

        source = MusicDto(theirs, 2, "banger", 70, "https://youtu.be/x".toByteArray(), 2_000, 9_000)

        val guild = mockk<Guild>(relaxed = true) {
            every { idLong } returns guildId
            every { id } returns guildId.toString()
        }
        val hook = mockk<InteractionHook>(relaxed = true)
        val send = mockk<WebhookMessageCreateAction<Message>>(relaxed = true)
        event = mockk(relaxed = true) {
            every { values } returns listOf("7_99_2")
            every { this@mockk.hook } returns hook
            every { user } returns mockk<User>(relaxed = true) { every { idLong } returns callerId }
        }
        ctx = mockk(relaxed = true) {
            every { this@mockk.event } returns this@CopyIntroMenuTest.event
            every { this@mockk.guild } returns guild
        }

        replies.clear()
        every { hook.sendMessage(capture(replies)) } returns send
        every { send.setEphemeral(any()) } returns send

        every { introHelper.findIntroById("7_99_2") } returns source
        every { introHelper.findUserById(callerId, guildId) } returns me
        every { introHelper.freeSlotFor(me) } returns 3
        every { introHelper.copyIntroInto(any(), any(), any(), any()) } returns source
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `picking an intro copies it into your first free slot`() {
        menu.handle(ctx, 5)

        verify { introHelper.copyIntroInto(source, me, 3, null) }
        assertTrue(replies.single().contains("banger"), replies.single())
        assertTrue(replies.single().contains("slot #3"), replies.single())
    }

    @Test
    fun `the confirmation echoes the volume and clip that came across`() {
        menu.handle(ctx, 5)

        assertTrue(replies.single().contains("70%"), replies.single())
        assertTrue(replies.single().contains("0:02 – 0:09"), replies.single())
    }

    @Test
    fun `the option value alone is enough to find the source again`() {
        // The id is guildId_discordId_slot, so nothing has to be parked
        // between opening the menu and answering it — and nothing can expire.
        menu.handle(ctx, 5)

        verify { introHelper.findIntroById("7_99_2") }
    }

    @Test
    fun `an intro deleted since the menu opened is reported, not copied`() {
        every { introHelper.findIntroById("7_99_2") } returns null

        menu.handle(ctx, 5)

        verify(exactly = 0) { introHelper.copyIntroInto(any(), any(), any(), any()) }
        assertTrue(replies.single().contains("has gone"), replies.single())
    }

    @Test
    fun `a submission with nothing selected is reported rather than throwing`() {
        every { event.values } returns emptyList()

        menu.handle(ctx, 5)

        verify(exactly = 0) { introHelper.copyIntroInto(any(), any(), any(), any()) }
        assertTrue(replies.single().contains("has gone"), replies.single())
    }

    @Test
    fun `at the slot limit it explains both ways to make room`() {
        // The menu is one click, so there is no `into:` here — that is what
        // the slash command is for.
        every { introHelper.freeSlotFor(me) } returns null

        menu.handle(ctx, 5)

        verify(exactly = 0) { introHelper.copyIntroInto(any(), any(), any(), any()) }
        assertTrue(replies.single().contains("/deleteintro"), replies.single())
        assertTrue(replies.single().contains("into:"), replies.single())
    }

    @Test
    fun `a refused copy is reported rather than passing silently`() {
        every { introHelper.copyIntroInto(any(), any(), any(), any()) } returns null

        menu.handle(ctx, 5)

        assertTrue(replies.single().contains("Couldn't copy"), replies.single())
    }

    @Test
    fun `the menu is built with one option per intro, valued by id`() {
        val built = CopyIntroMenu.menu(
            listOf(
                MusicDto(theirs, 1, "first", 90, byteArrayOf(1)),
                MusicDto(theirs, 2, "second", 90, byteArrayOf(1)),
            )
        )

        assertEquals(CopyIntroMenu.MENU_NAME, built.customId)
        assertEquals(listOf("7_99_1", "7_99_2"), built.options.map { it.value })
        assertEquals(listOf("first", "second"), built.options.map { it.label })
    }
}
