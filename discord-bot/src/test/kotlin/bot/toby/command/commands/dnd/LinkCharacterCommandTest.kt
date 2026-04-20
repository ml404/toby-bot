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
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        characterSheetProvider = mockk(relaxed = true)
        userDtoHelper = mockk(relaxed = true)
        every { event.deferReply(true) } returns replyCallbackAction
        every { requestingUserDto.dndBeyondCharacterId = any() } just Runs
        every { requestingUserDto.initiativeModifier = any() } just Runs
        command = LinkCharacterCommand(dispatcher, characterSheetProvider, userDtoHelper)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
    }

    @Test
    fun `valid dndbeyond URL links character and syncs DEX modifier`() = runTest {
        val optionMapping = mockk<OptionMapping>()
        every { event.getOption(LinkCharacterCommand.CHARACTER) } returns optionMapping
        every { optionMapping.asString } returns "https://www.dndbeyond.com/characters/48690485"
        coEvery { characterSheetProvider.getCharacterSheet(48690485L) } returns mockCharacter

        command.handle(DefaultCommandContext(event), requestingUserDto, deleteDelay)

        coVerify { characterSheetProvider.getCharacterSheet(48690485L) }
        verify { requestingUserDto.dndBeyondCharacterId = 48690485L }
        verify { requestingUserDto.initiativeModifier = 2 } // DEX 14 -> +2
        verify { userDtoHelper.updateUser(requestingUserDto) }
        verify { event.hook.sendMessageEmbeds(any(), *anyVararg()) }
    }

    @Test
    fun `plain numeric ID links character`() = runTest {
        val optionMapping = mockk<OptionMapping>()
        every { event.getOption(LinkCharacterCommand.CHARACTER) } returns optionMapping
        every { optionMapping.asString } returns "48690485"
        coEvery { characterSheetProvider.getCharacterSheet(48690485L) } returns mockCharacter

        command.handle(DefaultCommandContext(event), requestingUserDto, deleteDelay)

        coVerify { characterSheetProvider.getCharacterSheet(48690485L) }
        verify { requestingUserDto.dndBeyondCharacterId = 48690485L }
    }

    @Test
    fun `API service URL links character`() = runTest {
        val optionMapping = mockk<OptionMapping>()
        every { event.getOption(LinkCharacterCommand.CHARACTER) } returns optionMapping
        every { optionMapping.asString } returns "https://character-service.dndbeyond.com/character/v5/character/48690485"
        coEvery { characterSheetProvider.getCharacterSheet(48690485L) } returns mockCharacter

        command.handle(DefaultCommandContext(event), requestingUserDto, deleteDelay)

        coVerify { characterSheetProvider.getCharacterSheet(48690485L) }
        verify { requestingUserDto.dndBeyondCharacterId = 48690485L }
    }

    @Test
    fun `invalid input with no digits replies with error`() = runTest {
        val optionMapping = mockk<OptionMapping>()
        every { event.getOption(LinkCharacterCommand.CHARACTER) } returns optionMapping
        every { optionMapping.asString } returns "not-a-valid-url"

        command.handle(DefaultCommandContext(event), requestingUserDto, deleteDelay)

        verify { event.hook.sendMessage(any<String>()) }
        verify(exactly = 0) { userDtoHelper.updateUser(any()) }
        coVerify(exactly = 0) { characterSheetProvider.getCharacterSheet(any()) }
    }

    @Test
    fun `no cached sheet still links id and replies with plain message`() = runTest {
        val optionMapping = mockk<OptionMapping>()
        every { event.getOption(LinkCharacterCommand.CHARACTER) } returns optionMapping
        every { optionMapping.asString } returns "99999999"
        coEvery { characterSheetProvider.getCharacterSheet(99999999L) } returns null

        command.handle(DefaultCommandContext(event), requestingUserDto, deleteDelay)

        verify { requestingUserDto.dndBeyondCharacterId = 99999999L }
        verify { userDtoHelper.updateUser(requestingUserDto) }
        verify { event.hook.sendMessage(any<String>()) }
    }

    @Test
    fun `extractCharacterId correctly parses various URL formats`() {
        assertEquals(48690485L, command.extractCharacterId("https://www.dndbeyond.com/characters/48690485"))
        assertEquals(48690485L, command.extractCharacterId("48690485"))
        assertEquals(48690485L, command.extractCharacterId("https://character-service.dndbeyond.com/character/v5/character/48690485"))
        assertNull(command.extractCharacterId("not-a-valid-url"))
    }
}
