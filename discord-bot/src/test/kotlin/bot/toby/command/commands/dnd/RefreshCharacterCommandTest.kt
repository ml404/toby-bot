package bot.toby.command.commands.dnd

import bot.coroutines.MainCoroutineExtension
import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.replyCallbackAction
import bot.toby.command.DefaultCommandContext
import bot.toby.dto.web.dnd.AbilityStat
import bot.toby.dto.web.dnd.CharacterSheet
import bot.toby.helpers.charactersheet.CharacterSheetProvider
import bot.toby.helpers.charactersheet.CharacterSheetProvider.FetchResult
import database.dto.UserDto
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainCoroutineExtension::class)
class RefreshCharacterCommandTest : CommandTest {

    private lateinit var command: RefreshCharacterCommand
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var characterSheetProvider: CharacterSheetProvider
    private lateinit var linkedUserDto: UserDto
    private lateinit var unlinkedUserDto: UserDto
    private val deleteDelay = 0

    private val mockCharacter = CharacterSheet(
        id = 48690485L,
        name = "Eeyore",
        stats = listOf(
            AbilityStat(1, 10), AbilityStat(2, 14), AbilityStat(3, 17),
            AbilityStat(4, 15), AbilityStat(5, 18), AbilityStat(6, 10)
        ),
        baseHitPoints = 68,
        bonusHitPoints = null,
        removedHitPoints = 0,
        temporaryHitPoints = 0,
        race = null,
        classes = null
    )

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        characterSheetProvider = mockk(relaxed = true)

        linkedUserDto = mockk(relaxed = true)
        every { linkedUserDto.dndBeyondCharacterId } returns 48690485L

        unlinkedUserDto = mockk(relaxed = true)
        every { unlinkedUserDto.dndBeyondCharacterId } returns null

        every { event.deferReply(true) } returns replyCallbackAction

        command = RefreshCharacterCommand(dispatcher, characterSheetProvider)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
    }

    @Test
    fun `success sends embed`() = runTest {
        coEvery { characterSheetProvider.fetchCharacterSheet(48690485L) } returns
            FetchResult.Success(mockCharacter, "{}")

        command.handle(DefaultCommandContext(event), linkedUserDto, deleteDelay)

        verify { event.hook.sendMessageEmbeds(any(), *anyVararg()) }
    }

    @Test
    fun `forbidden replies with private character message`() = runTest {
        coEvery { characterSheetProvider.fetchCharacterSheet(48690485L) } returns FetchResult.Forbidden

        command.handle(DefaultCommandContext(event), linkedUserDto, deleteDelay)

        verify { event.hook.sendMessage(match<String> { it.contains("private", ignoreCase = true) }) }
    }

    @Test
    fun `not found replies with error message`() = runTest {
        coEvery { characterSheetProvider.fetchCharacterSheet(48690485L) } returns FetchResult.NotFound

        command.handle(DefaultCommandContext(event), linkedUserDto, deleteDelay)

        verify { event.hook.sendMessage(match<String> { it.contains("could not find") }) }
    }

    @Test
    fun `unavailable replies with reachability message`() = runTest {
        coEvery { characterSheetProvider.fetchCharacterSheet(48690485L) } returns FetchResult.Unavailable()

        command.handle(DefaultCommandContext(event), linkedUserDto, deleteDelay)

        verify { event.hook.sendMessage(match<String> { it.contains("unreachable") }) }
    }

    @Test
    fun `no linked character replies with guidance`() = runTest {
        command.handle(DefaultCommandContext(event), unlinkedUserDto, deleteDelay)

        coVerify(exactly = 0) { characterSheetProvider.fetchCharacterSheet(any()) }
        verify { event.hook.sendMessage(match<String> { it.contains("/linkcharacter") }) }
    }
}
