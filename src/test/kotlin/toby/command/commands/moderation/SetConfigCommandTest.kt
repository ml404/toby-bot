package toby.command.commands.moderation

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.entities.channel.unions.GuildChannelUnion
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.command.CommandContext
import toby.command.CommandTest
import toby.jpa.dto.ConfigDto
import toby.jpa.dto.ConfigDto.Configurations.*
import toby.jpa.service.IConfigService
import java.util.*

internal class SetConfigCommandTest : CommandTest {
    lateinit var setConfigCommand: SetConfigCommand
    lateinit var configService: IConfigService

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        configService = mockk()
        setConfigCommand = SetConfigCommand(configService)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearMocks(configService)
    }

    @Test
    fun testSetConfig_notAsServerOwner_sendsErrorMessage() {
        // Arrange
        val commandContext = CommandContext(CommandTest.event)
        val volumeOptionMapping = mockk<OptionMapping>()

        every { CommandTest.event.getOption(VOLUME.name.lowercase(Locale.getDefault())) } returns volumeOptionMapping
        every { volumeOptionMapping.asInt } returns 20
        every { volumeOptionMapping.name } returns VOLUME.name
        every { CommandTest.member.isOwner } returns false
        every { CommandTest.event.options } returns listOf(volumeOptionMapping)
        every { configService.getConfigByName(VOLUME.name, "1") } returns null

        // Act
        setConfigCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        // Assert
        verify(exactly = 0) { configService.getConfigByName(VOLUME.configValue, "1") }
        verify(exactly = 0) { configService.createNewConfig(any()) }
        verify(exactly = 1) {
            CommandTest.interactionHook.sendMessage("This is currently reserved for the owner of the server only, this may change in future")
        }
    }

    @Test
    fun testSetConfig_withOneConfig_createsThatConfig() {
        // Arrange
        val commandContext = CommandContext(CommandTest.event)
        val volumeOptionMapping = mockk<OptionMapping>()

        every { CommandTest.event.getOption(VOLUME.name.lowercase(Locale.getDefault())) } returns volumeOptionMapping
        every { volumeOptionMapping.asInt } returns 20
        every { volumeOptionMapping.name } returns VOLUME.name
        every { CommandTest.member.isOwner } returns true
        every { CommandTest.event.options } returns listOf(volumeOptionMapping)
        every { configService.getConfigByName(VOLUME.configValue, "1") } returns null

        // Act
        setConfigCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { configService.getConfigByName(VOLUME.configValue, "1") }
        verify(exactly = 1) { configService.createNewConfig(any()) }
        verify(exactly = 1) {
            CommandTest.interactionHook.sendMessageFormat("Set default volume to '%s'", 20)
        }
    }

    @Test
    fun testSetConfig_withOneConfig_updatesThatConfig() {
        // Arrange
        val commandContext = CommandContext(CommandTest.event)
        val volumeOptionMapping = mockk<OptionMapping>()
        val dbConfig = ConfigDto(VOLUME.configValue, "20", "1")

        every { CommandTest.event.getOption(VOLUME.name.lowercase(Locale.getDefault())) } returns volumeOptionMapping
        every { volumeOptionMapping.asInt } returns 20
        every { volumeOptionMapping.name } returns VOLUME.name
        every { CommandTest.member.isOwner } returns true
        every { CommandTest.event.options } returns listOf(volumeOptionMapping)
        every { configService.getConfigByName(VOLUME.configValue, "1") } returns dbConfig

        // Act
        setConfigCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { configService.getConfigByName(VOLUME.configValue, "1") }
        verify(exactly = 1) { configService.updateConfig(any<ConfigDto>()) }
        verify(exactly = 1) {
            CommandTest.interactionHook.sendMessageFormat("Set default volume to '%s'", 20)
        }
    }

    @Test
    fun testSetConfig_withDeleteDelay_createsThatConfig() {
        // Arrange
        val commandContext = CommandContext(CommandTest.event)
        val deleteDelayOptionMapping = mockk<OptionMapping>()

        every { CommandTest.event.getOption(DELETE_DELAY.name.lowercase(Locale.getDefault())) } returns deleteDelayOptionMapping
        every { deleteDelayOptionMapping.asInt } returns 20
        every { deleteDelayOptionMapping.name } returns DELETE_DELAY.name
        every { CommandTest.member.isOwner } returns true
        every { CommandTest.event.options } returns listOf(deleteDelayOptionMapping)
        every { configService.getConfigByName(DELETE_DELAY.configValue, "1") } returns null

        // Act
        setConfigCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { configService.getConfigByName(DELETE_DELAY.configValue, "1") }
        verify(exactly = 1) { configService.createNewConfig(any()) }
        verify(exactly = 1) {
            CommandTest.interactionHook.sendMessageFormat(
                "Set default delete message delay for TobyBot music messages to '%d' seconds", 20
            )
        }
    }

    @Test
    fun testSetConfig_withMultipleSpecified_createsTwoConfig() {
        // Arrange
        val commandContext = CommandContext(CommandTest.event)
        val deleteDelayOptionMapping = mockk<OptionMapping>()
        val volumeOptionMapping = mockk<OptionMapping>()

        every { CommandTest.event.getOption(DELETE_DELAY.name.lowercase(Locale.getDefault())) } returns deleteDelayOptionMapping
        every { deleteDelayOptionMapping.asInt } returns 20
        every { deleteDelayOptionMapping.name } returns DELETE_DELAY.name
        every { CommandTest.event.getOption(VOLUME.name.lowercase(Locale.getDefault())) } returns volumeOptionMapping
        every { volumeOptionMapping.asInt } returns 20
        every { volumeOptionMapping.name } returns VOLUME.name
        every { CommandTest.member.isOwner } returns true
        every { CommandTest.event.options } returns listOf(deleteDelayOptionMapping, volumeOptionMapping)
        every { configService.getConfigByName(DELETE_DELAY.configValue, "1") } returns null
        every { configService.getConfigByName(VOLUME.configValue, "1") } returns null

        // Act
        setConfigCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { configService.getConfigByName(DELETE_DELAY.configValue, "1") }
        verify(exactly = 1) { configService.getConfigByName(VOLUME.configValue, "1") }
        verify(exactly = 2) { configService.createNewConfig(any()) }
        verify(exactly = 1) {
            CommandTest.interactionHook.sendMessageFormat("Set default volume to '%s'", 20)
        }
        verify(exactly = 1) {
            CommandTest.interactionHook.sendMessageFormat(
                "Set default delete message delay for TobyBot music messages to '%d' seconds", 20
            )
        }
    }

    @Test
    fun testSetConfig_withMoveChannel_createsThatConfig() {
        // Arrange
        val commandContext = CommandContext(CommandTest.event)
        val moveOptionMapping = mockk<OptionMapping>()
        val guildChannelUnion = mockk<GuildChannelUnion>()

        every { CommandTest.event.getOption(MOVE.name.lowercase(Locale.getDefault())) } returns moveOptionMapping
        every { moveOptionMapping.asChannel } returns guildChannelUnion
        every { moveOptionMapping.name } returns MOVE.name.lowercase(Locale.getDefault())
        every { guildChannelUnion.name } returns "Channel Name"
        every { CommandTest.member.isOwner } returns true
        every { CommandTest.event.options } returns listOf(moveOptionMapping)
        every { configService.getConfigByName(MOVE.configValue, "1") } returns null

        // Act
        setConfigCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { configService.getConfigByName(MOVE.configValue, "1") }
        verify(exactly = 1) { configService.createNewConfig(any()) }
        verify(exactly = 1) {
            CommandTest.interactionHook.sendMessageFormat("Set default move channel to '%s'", "Channel Name")
        }
    }
}
