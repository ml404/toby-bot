package bot.toby.command.commands.dnd

import bot.coroutines.MainCoroutineExtension
import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.interactionHook
import bot.toby.command.CommandTest.Companion.member
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.CommandTest.Companion.targetMember
import bot.toby.command.DefaultCommandContext
import bot.toby.dto.web.dnd.AbilityStat
import bot.toby.dto.web.dnd.CharacterSheet
import bot.toby.helpers.UserDtoHelper
import bot.toby.helpers.charactersheet.CharacterSheetProvider
import database.dto.UserDto
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
class CharacterCommandTest : CommandTest {

    private lateinit var command: CharacterCommand
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var characterSheetProvider: CharacterSheetProvider
    private lateinit var userDtoHelper: UserDtoHelper
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
        userDtoHelper = mockk(relaxed = true)

        linkedUserDto = mockk(relaxed = true)
        every { linkedUserDto.dndBeyondCharacterId } returns 48690485L

        unlinkedUserDto = mockk(relaxed = true)
        every { unlinkedUserDto.dndBeyondCharacterId } returns null

        every { member.idLong } returns 1L
        every { member.isOwner } returns false
        every { targetMember.idLong } returns 2L
        every { targetMember.isOwner } returns false
        every { targetMember.guild } returns CommandTest.guild
        every { targetMember.effectiveName } returns "Target Name"

        command = CharacterCommand(dispatcher, characterSheetProvider, userDtoHelper)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
    }

    @Test
    fun `no user option shows calling member character`() = runTest {
        every { event.getOption(CharacterCommand.USER) } returns null
        every { userDtoHelper.calculateUserDto(1L, 1L, false) } returns linkedUserDto
        coEvery { characterSheetProvider.getCharacterSheet(48690485L) } returns mockCharacter

        command.handle(DefaultCommandContext(event), requestingUserDto, deleteDelay)

        coVerify { characterSheetProvider.getCharacterSheet(48690485L) }
        verify { event.hook.sendMessageEmbeds(any(), *anyVararg()) }
    }

    @Test
    fun `user option shows target member character`() = runTest {
        val userOptionMapping = mockk<OptionMapping>()
        every { event.getOption(CharacterCommand.USER) } returns userOptionMapping
        every { userOptionMapping.asMember } returns targetMember
        every { userDtoHelper.calculateUserDto(2L, 1L, false) } returns linkedUserDto
        coEvery { characterSheetProvider.getCharacterSheet(48690485L) } returns mockCharacter

        command.handle(DefaultCommandContext(event), requestingUserDto, deleteDelay)

        coVerify { characterSheetProvider.getCharacterSheet(48690485L) }
        verify { event.hook.sendMessageEmbeds(any(), *anyVararg()) }
    }

    @Test
    fun `no linked character for self replies with message`() = runTest {
        every { event.getOption(CharacterCommand.USER) } returns null
        every { userDtoHelper.calculateUserDto(1L, 1L, false) } returns unlinkedUserDto

        command.handle(DefaultCommandContext(event), requestingUserDto, deleteDelay)

        coVerify(exactly = 0) { characterSheetProvider.getCharacterSheet(any()) }
        verify { event.hook.sendMessage(any<String>()) }
    }

    @Test
    fun `no linked character for target member replies with message`() = runTest {
        val userOptionMapping = mockk<OptionMapping>()
        every { event.getOption(CharacterCommand.USER) } returns userOptionMapping
        every { userOptionMapping.asMember } returns targetMember
        every { userDtoHelper.calculateUserDto(2L, 1L, false) } returns unlinkedUserDto

        command.handle(DefaultCommandContext(event), requestingUserDto, deleteDelay)

        coVerify(exactly = 0) { characterSheetProvider.getCharacterSheet(any()) }
        verify { event.hook.sendMessage(any<String>()) }
    }

    @Test
    fun `character fetch returns null replies with error`() = runTest {
        every { event.getOption(CharacterCommand.USER) } returns null
        every { userDtoHelper.calculateUserDto(1L, 1L, false) } returns linkedUserDto
        coEvery { characterSheetProvider.getCharacterSheet(48690485L) } returns null

        command.handle(DefaultCommandContext(event), requestingUserDto, deleteDelay)

        verify(exactly = 0) { interactionHook.sendMessageEmbeds(any(), *anyVararg()) }
        verify { event.hook.sendMessage(any<String>()) }
    }
}
