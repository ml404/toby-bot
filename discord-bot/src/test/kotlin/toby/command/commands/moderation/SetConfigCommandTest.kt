package toby.command.commands.moderation

import database.dto.ConfigDto
import database.dto.ConfigDto.Configurations.*
import database.service.IConfigService
import io.mockk.*
import net.dv8tion.jda.api.entities.channel.unions.GuildChannelUnion
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.CommandTest.Companion.event
import toby.command.CommandTest.Companion.member
import toby.command.CommandTest.Companion.requestingUserDto
import java.util.*

internal class SetConfigCommandTest : CommandTest {
    lateinit var setConfigCommand: SetConfigCommand
    lateinit var configService: IConfigService

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        configService = mockk(relaxed = true)
        setConfigCommand = SetConfigCommand(configService)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearAllMocks()
    }

    @Test
    fun testSetConfig_notAsServerOwner_sendsErrorMessage() {
        // Arrange
        val commandContext = CommandContext(event)
        val volumeOptionMapping = mockk<OptionMapping>()

        every { event.getOption(VOLUME.name.lowercase(Locale.getDefault())) } returns volumeOptionMapping
        every { volumeOptionMapping.asInt } returns 20
        every { volumeOptionMapping.name } returns VOLUME.name
        every { member.isOwner } returns false
        every { event.options } returns listOf(volumeOptionMapping)
        every { configService.getConfigByName(VOLUME.name, "1") } returns null

        // Act
        setConfigCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 0) { configService.getConfigByName(VOLUME.configValue, "1") }
        verify(exactly = 0) { configService.createNewConfig(any()) }
        verify(exactly = 1) {
            event.hook.sendMessage("This is currently reserved for the owner of the server only, this may change in future")
        }
    }

    @Test
    fun testSetConfig_withOneConfig_createsThatConfig() {
        // Arrange
        val commandContext = CommandContext(event)
        val volumeOptionMapping = mockk<OptionMapping>()

        every { event.getOption(VOLUME.name.lowercase(Locale.getDefault())) } returns volumeOptionMapping
        every { volumeOptionMapping.asInt } returns 20
        every { volumeOptionMapping.name } returns VOLUME.name
        every { member.isOwner } returns true
        every { event.options } returns listOf(volumeOptionMapping)
        every { configService.getConfigByName(VOLUME.configValue, "1") } returns null

        // Act
        setConfigCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { configService.getConfigByName(VOLUME.configValue, "1") }
        verify(exactly = 1) { configService.createNewConfig(any()) }
        verify(exactly = 1) {
            event.hook.sendMessage("Set default volume to '20'")
        }
    }

    @Test
    fun testSetConfig_withOneConfig_updatesThatConfig() {
        // Arrange
        val commandContext = CommandContext(event)
        val volumeOptionMapping = mockk<OptionMapping>()
        val dbConfig = ConfigDto(VOLUME.configValue, "20", "1")


        every { event.getOption(VOLUME.name.lowercase(Locale.getDefault())) } returns volumeOptionMapping
        every { volumeOptionMapping.asInt } returns 20
        every { volumeOptionMapping.name } returns VOLUME.name
        every { member.isOwner } returns true
        every { event.options } returns listOf(volumeOptionMapping)
        every { configService.getConfigByName(VOLUME.configValue, "1") } returns dbConfig

        // Act
        setConfigCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { configService.getConfigByName(VOLUME.configValue, "1") }
        verify(exactly = 1) { configService.updateConfig(any<ConfigDto>()) }
        verify(exactly = 1) {
            event.hook.sendMessage("Set default volume to '20'")
        }
    }

    @Test
    fun testSetConfig_withDeleteDelay_createsThatConfig() {
        // Arrange
        val commandContext = CommandContext(event)
        val deleteDelayOptionMapping = mockk<OptionMapping>()
        every { configService.createNewConfig(any()) } returns ConfigDto(DELETE_DELAY.name, "20", "1")

        every { event.getOption(DELETE_DELAY.name.lowercase(Locale.getDefault())) } returns deleteDelayOptionMapping
        every { deleteDelayOptionMapping.asInt } returns 20
        every { deleteDelayOptionMapping.name } returns DELETE_DELAY.name
        every { member.isOwner } returns true
        every { event.options } returns listOf(deleteDelayOptionMapping)
        every { configService.getConfigByName(DELETE_DELAY.configValue, "1") } returns null

        // Act
        setConfigCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { configService.getConfigByName(DELETE_DELAY.configValue, "1") }
        verify(exactly = 1) { configService.createNewConfig(any()) }
        verify(exactly = 1) {
            event.hook.sendMessage(
                "Set default delete message delay for TobyBot music messages to '20' seconds"
            )
        }
    }

    @Test
    fun testSetConfig_withMultipleSpecified_createsTwoConfig() {
        // Arrange
        val commandContext = CommandContext(event)
        val deleteDelayOptionMapping = mockk<OptionMapping>()
        val volumeOptionMapping = mockk<OptionMapping>()

        every { event.member } returns member
        every { member.isOwner } returns true
        every { event.options } returns listOf(deleteDelayOptionMapping, volumeOptionMapping)
        every { deleteDelayOptionMapping.name } returns ConfigDto.Configurations.DELETE_DELAY.name.lowercase(Locale.getDefault())
        every { deleteDelayOptionMapping.asInt } returns 20
        every { volumeOptionMapping.name } returns ConfigDto.Configurations.VOLUME.name.lowercase(Locale.getDefault())
        every { volumeOptionMapping.asInt } returns 20
        every { event.guild } returns mockk {
            every { id } returns "1"
        }
        every { configService.getConfigByName(ConfigDto.Configurations.DELETE_DELAY.configValue, "1") } returns null
        every { configService.getConfigByName(ConfigDto.Configurations.VOLUME.configValue, "1") } returns null
        every { configService.createNewConfig(any()) } returns null
        every { event.hook.sendMessage(any<String>()) } returns mockk {
            every { setEphemeral(any()) } returns this
            every { queue(any()) } just Runs
        }

        // Act
        setConfigCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { configService.getConfigByName(ConfigDto.Configurations.DELETE_DELAY.configValue, "1") }
        verify(exactly = 1) { configService.getConfigByName(ConfigDto.Configurations.VOLUME.configValue, "1") }
        verify(exactly = 2) { configService.createNewConfig(any()) }
        verify(exactly = 2) {
            event.hook.sendMessage(any<String>())
        }
    }

    @Test
    fun testSetConfig_withMoveChannel_createsThatConfig() {
        // Arrange
        val commandContext = CommandContext(event)
        val moveOptionMapping = mockk<OptionMapping>()
        val guildChannelUnion = mockk<GuildChannelUnion>()

        every { event.getOption(MOVE.name.lowercase(Locale.getDefault())) } returns moveOptionMapping
        every { moveOptionMapping.asChannel } returns guildChannelUnion
        every { moveOptionMapping.name } returns MOVE.name.lowercase(Locale.getDefault())
        every { guildChannelUnion.name } returns "Channel Name"
        every { member.isOwner } returns true
        every { event.options } returns listOf(moveOptionMapping)
        every { configService.getConfigByName(MOVE.configValue, "1") } returns null

        // Act
        setConfigCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { configService.getConfigByName(MOVE.configValue, "1") }
        verify(exactly = 1) { configService.createNewConfig(any()) }
        verify(exactly = 1) {
            event.hook.sendMessage("Set default move channel to 'Channel Name'")
        }
    }
}
