package bot.toby.button.buttons.music

import bot.toby.button.ButtonTest
import bot.toby.button.ButtonTest.Companion.event
import bot.toby.button.ButtonTest.Companion.introHelper
import bot.toby.button.DefaultButtonContext
import bot.toby.intro.IntroUndoStore
import database.dto.music.MusicDto
import database.dto.user.UserDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.requests.restaction.interactions.MessageEditCallbackAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class UndoDeleteIntroButtonTest : ButtonTest {

    private lateinit var undoStore: IntroUndoStore
    private lateinit var button: UndoDeleteIntroButton
    private val edits = mutableListOf<String>()

    /** The clicking user, matching ButtonTest's event.user. */
    private val owner = UserDto(discordId = 1L, guildId = 1L)

    @BeforeEach
    override fun setup() {
        super.setup()
        undoStore = IntroUndoStore()
        button = UndoDeleteIntroButton(introHelper, undoStore)

        edits.clear()
        val editAction = mockk<MessageEditCallbackAction>(relaxed = true)
        every { event.editMessage(capture(edits)) } returns editAction
        every { editAction.setComponents() } returns editAction

        every { introHelper.findUserById(1L, 1L) } returns owner
        every { introHelper.createIntro(any()) } answers { firstArg() }
    }

    @AfterEach
    override fun tearDown() {
        super.tearDown()
        unmockkAll()
    }

    private fun deleted(index: Int = 2, name: String = "track") =
        MusicDto(owner, index, name, 40, "https://example.com/a".toByteArray(), 3_000, 9_000)

    @Test
    fun `restores the snapshot into its original slot`() {
        undoStore.remember(deleted())
        owner.musicDtos = mutableListOf()

        button.handle(DefaultButtonContext(event), owner, 0)

        val restored = slot<MusicDto>()
        verify { introHelper.createIntro(capture(restored)) }
        assertEquals(2, restored.captured.index)
        assertEquals("track", restored.captured.fileName)
        assertEquals(40, restored.captured.introVolume)
        assertEquals(3_000, restored.captured.startMs)
        assertEquals(9_000, restored.captured.endMs)
        assertTrue(edits.single().contains("Restored"))
    }

    @Test
    fun `falls back to the lowest free slot when the original was reclaimed`() {
        undoStore.remember(deleted(index = 2))
        owner.musicDtos = mutableListOf(MusicDto(owner, 2, "something else", 90, byteArrayOf(1)))

        button.handle(DefaultButtonContext(event), owner, 0)

        val restored = slot<MusicDto>()
        verify { introHelper.createIntro(capture(restored)) }
        assertEquals(1, restored.captured.index)
    }

    @Test
    fun `the restored slot stays inside the slot range when the remaining intros leave a gap`() {
        undoStore.remember(deleted(index = 3))
        owner.musicDtos = mutableListOf(MusicDto(owner, 3, "something else", 90, byteArrayOf(1)))

        button.handle(DefaultButtonContext(event), owner, 0)

        val restored = slot<MusicDto>()
        verify { introHelper.createIntro(capture(restored)) }
        assertEquals(1, restored.captured.index)
    }

    @Test
    fun `an expired or already-used undo says so instead of restoring`() {
        button.handle(DefaultButtonContext(event), owner, 0)

        verify(exactly = 0) { introHelper.createIntro(any()) }
        assertTrue(edits.single().contains("Nothing to undo"))
    }

    @Test
    fun `undo is refused once the slots have filled back up`() {
        undoStore.remember(deleted())
        owner.musicDtos = mutableListOf(
            MusicDto(owner, 1, "a", 90, byteArrayOf(1)),
            MusicDto(owner, 2, "b", 90, byteArrayOf(2)),
            MusicDto(owner, 3, "c", 90, byteArrayOf(3)),
        )

        button.handle(DefaultButtonContext(event), owner, 0)

        verify(exactly = 0) { introHelper.createIntro(any()) }
        assertTrue(edits.single().contains("Can't restore"))
    }

    @Test
    fun `a rejected re-insert is reported rather than silently swallowed`() {
        undoStore.remember(deleted())
        owner.musicDtos = mutableListOf()
        every { introHelper.createIntro(any()) } returns null

        button.handle(DefaultButtonContext(event), owner, 0)

        assertTrue(edits.single().contains("Couldn't restore"))
    }

    @Test
    fun `undo is single use`() {
        undoStore.remember(deleted())
        owner.musicDtos = mutableListOf()

        button.handle(DefaultButtonContext(event), owner, 0)
        button.handle(DefaultButtonContext(event), owner, 0)

        verify(exactly = 1) { introHelper.createIntro(any()) }
        assertTrue(edits.last().contains("Nothing to undo"))
    }
}
