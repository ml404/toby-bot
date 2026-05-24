package bot.toby.command.commands.misc

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.member
import bot.toby.command.CommandTest.Companion.webhookMessageCreateAction
import bot.toby.command.DefaultCommandContext
import bot.toby.helpers.UserDtoHelper
import database.dto.user.UserDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LevelCommandTest : CommandTest {
    private lateinit var userDtoHelper: UserDtoHelper
    private lateinit var levelCommand: LevelCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        userDtoHelper = mockk()
        levelCommand = LevelCommand(userDtoHelper)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
    }

    @Test
    fun `level command renders the user's level and progress`() {
        every { event.getOption("user") } returns null
        val dto = UserDto(discordId = 1L, guildId = 1L).apply { xp = 300L }
        every { userDtoHelper.calculateUserDto(any(), any()) } returns dto

        val captured = slot<String>()
        every { event.hook.sendMessage(capture(captured)) } returns webhookMessageCreateAction

        levelCommand.handle(DefaultCommandContext(event), dto, 0)

        verify(exactly = 1) { event.hook.sendMessage(any<String>()) }
        // 300 XP -> level 2, 45/220 into that level.
        assertTrue(captured.captured.contains("Level 2"))
        assertTrue(captured.captured.contains("45 / 220 XP"))
        assertTrue(captured.captured.contains("Total XP: 300"))
    }

    @Test
    fun `level command inspects another member when one is mentioned`() {
        val option = mockk<OptionMapping>()
        every { event.getOption("user") } returns option
        every { option.asMember } returns member
        val dto = UserDto(discordId = 1L, guildId = 1L).apply { xp = 99L }
        every { userDtoHelper.calculateUserDto(any(), any()) } returns dto

        val captured = slot<String>()
        every { event.hook.sendMessage(capture(captured)) } returns webhookMessageCreateAction

        levelCommand.handle(DefaultCommandContext(event), dto, 0)

        // Just under the first threshold (100 XP) -> still level 0 with 99/100.
        assertTrue(captured.captured.contains("Level 0"))
        assertTrue(captured.captured.contains("99 / 100 XP"))
        assertTrue(captured.captured.contains("Effective Name"))
    }
}
