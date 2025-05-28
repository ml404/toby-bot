package bot.toby.command.commands.music.intro

import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.commands.music.MusicCommandTest
import core.command.CommandContext
import database.dto.MusicDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DeleteIntroCommandTest : MusicCommandTest {
    private var deleteIntroCommand = DeleteIntroCommand()
    private lateinit var ctx: CommandContext

    @BeforeEach
    fun setup() {
        setupCommonMusicMocks()
        ctx = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMusicMocks()
        unmockkAll()
    }

    @Test
    fun `test handle with no intros`() {
        val mockCtx: CommandContext = mockk(relaxed = true)
        every { mockCtx.event } returns event
        every { requestingUserDto.musicDtos } returns mutableListOf()

        // Call handle
        deleteIntroCommand.handle(mockCtx, requestingUserDto, null)

        // Verify the reply indicating no intros
        verify { event.hook.sendMessage("You have no intros to delete.") }
    }

    @Test
    fun testHandleWithIntros() {
        every { ctx.event } returns event

        val intro1 = MusicDto(requestingUserDto, 1, "Intro1", introVolume = 20)
        val intro2 = MusicDto(requestingUserDto, 2, "Intro2", introVolume = 20)

        every { requestingUserDto.musicDtos } returns mutableListOf(intro1, intro2)

        deleteIntroCommand.handle(ctx, requestingUserDto, null)

        verify { event.hook.sendMessage(match<String> { it.contains("Please select an intro to delete") }) }
    }
}