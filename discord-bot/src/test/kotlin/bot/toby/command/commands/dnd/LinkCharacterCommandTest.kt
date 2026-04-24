package bot.toby.command.commands.dnd

import bot.coroutines.MainCoroutineExtension
import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.replyCallbackAction
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.DefaultCommandContext
import bot.toby.dto.web.dnd.AbilityStat
import bot.toby.dto.web.dnd.CharacterSheet
import bot.toby.helpers.UserDtoHelper
import bot.toby.helpers.charactersheet.CharacterSheetProvider
import bot.toby.helpers.charactersheet.CharacterSheetProvider.FetchResult
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainCoroutineExtension::class)
class LinkCharacterCommandTest : CommandTest {

    private lateinit var command: LinkCharacterCommand
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var characterSheetProvider: CharacterSheetProvider
    private lateinit var userDtoHelper: UserDtoHelper
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
    private val mockSheetJson = """{"id":48690485}"""

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        characterSheetProvider = mockk(relaxed = true)
        userDtoHelper = mockk(relaxed = true)
        every { event.deferReply(true) } returns replyCallbackAction
        every { requestingUserDto.dndBeyondCharacterId = any() } just Runs
        command = LinkCharacterCommand(dispatcher, characterSheetProvider, userDtoHelper)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
    }

    private fun givenInput(raw: String) {
        val optionMapping = mockk<OptionMapping>()
        every { event.getOption(LinkCharacterCommand.CHARACTER) } returns optionMapping
        every { optionMapping.asString } returns raw
    }

    @Test
    fun `valid dndbeyond URL links character and shows DEX modifier in embed`() = runTest {
        givenInput("https://www.dndbeyond.com/characters/48690485")
        coEvery { characterSheetProvider.fetchCharacterSheet(48690485L) } returns
            FetchResult.Success(mockCharacter, mockSheetJson)

        command.handle(DefaultCommandContext(event), requestingUserDto, deleteDelay)

        coVerify { characterSheetProvider.fetchCharacterSheet(48690485L) }
        verify { requestingUserDto.dndBeyondCharacterId = 48690485L }
        verify { userDtoHelper.updateUser(requestingUserDto) }
        verify { event.hook.sendMessageEmbeds(any(), *anyVararg()) }
    }

    @Test
    fun `plain numeric ID links character`() = runTest {
        givenInput("48690485")
        coEvery { characterSheetProvider.fetchCharacterSheet(48690485L) } returns
            FetchResult.Success(mockCharacter, mockSheetJson)

        command.handle(DefaultCommandContext(event), requestingUserDto, deleteDelay)

        coVerify { characterSheetProvider.fetchCharacterSheet(48690485L) }
        verify { requestingUserDto.dndBeyondCharacterId = 48690485L }
    }

    @Test
    fun `API service URL links character`() = runTest {
        givenInput("https://character-service.dndbeyond.com/character/v5/character/48690485")
        coEvery { characterSheetProvider.fetchCharacterSheet(48690485L) } returns
            FetchResult.Success(mockCharacter, mockSheetJson)

        command.handle(DefaultCommandContext(event), requestingUserDto, deleteDelay)

        coVerify { characterSheetProvider.fetchCharacterSheet(48690485L) }
        verify { requestingUserDto.dndBeyondCharacterId = 48690485L }
    }

    @Test
    fun `invalid input with no digits replies with error`() = runTest {
        givenInput("not-a-valid-url")

        command.handle(DefaultCommandContext(event), requestingUserDto, deleteDelay)

        verify { event.hook.sendMessage(any<String>()) }
        verify(exactly = 0) { userDtoHelper.updateUser(any()) }
        coVerify(exactly = 0) { characterSheetProvider.fetchCharacterSheet(any()) }
    }

    @Test
    fun `forbidden response does not save id`() = runTest {
        givenInput("99999999")
        coEvery { characterSheetProvider.fetchCharacterSheet(99999999L) } returns FetchResult.Forbidden

        command.handle(DefaultCommandContext(event), requestingUserDto, deleteDelay)

        verify(exactly = 0) { requestingUserDto.dndBeyondCharacterId = any() }
        verify(exactly = 0) { userDtoHelper.updateUser(any()) }
        verify { event.hook.sendMessage(match<String> { it.contains("private", ignoreCase = true) }) }
    }

    @Test
    fun `not found response does not save id`() = runTest {
        givenInput("99999999")
        coEvery { characterSheetProvider.fetchCharacterSheet(99999999L) } returns FetchResult.NotFound

        command.handle(DefaultCommandContext(event), requestingUserDto, deleteDelay)

        verify(exactly = 0) { requestingUserDto.dndBeyondCharacterId = any() }
        verify(exactly = 0) { userDtoHelper.updateUser(any()) }
        verify { event.hook.sendMessage(match<String> { it.contains("No D&D Beyond character") }) }
    }

    @Test
    fun `unavailable response saves id with warning`() = runTest {
        givenInput("99999999")
        coEvery { characterSheetProvider.fetchCharacterSheet(99999999L) } returns FetchResult.Unavailable()

        command.handle(DefaultCommandContext(event), requestingUserDto, deleteDelay)

        verify { requestingUserDto.dndBeyondCharacterId = 99999999L }
        verify { userDtoHelper.updateUser(requestingUserDto) }
        verify { event.hook.sendMessage(match<String> { it.contains("unreachable") }) }
    }
}
