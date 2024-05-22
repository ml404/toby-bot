package toby.command.commands.moderation

import net.dv8tion.jda.api.entities.channel.unions.GuildChannelUnion
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito
import toby.command.CommandContext
import toby.command.CommandTest
import toby.jpa.dto.ConfigDto
import toby.jpa.service.IConfigService
import java.util.*

internal class SetConfigCommandTest : CommandTest {
    private lateinit var setConfigCommand: SetConfigCommand

    @Mock
    lateinit var configService: IConfigService

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        configService = Mockito.mock(IConfigService::class.java)
        setConfigCommand = SetConfigCommand(configService)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        Mockito.reset(configService)
    }

    @Test
    fun testSetConfig_notAsServerOwner_sendsErrorMessage() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val volumeOptionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(
            CommandTest.event.getOption(
                ConfigDto.Configurations.VOLUME.name.lowercase(
                    Locale.getDefault()
                )
            )
        ).thenReturn(volumeOptionMapping)
        Mockito.`when`(volumeOptionMapping.asInt).thenReturn(20)
        Mockito.`when`(volumeOptionMapping.name).thenReturn(ConfigDto.Configurations.VOLUME.name)
        Mockito.`when`(CommandTest.member.isOwner).thenReturn(false)
        Mockito.`when`<List<OptionMapping>>(CommandTest.event.options)
            .thenReturn(listOf(volumeOptionMapping))
        Mockito.`when`(configService.getConfigByName(ConfigDto.Configurations.VOLUME.name, "1")).thenReturn(null)

        //Act
        setConfigCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(configService, Mockito.times(0))
            .getConfigByName(ConfigDto.Configurations.VOLUME.configValue, "1")
        Mockito.verify(configService, Mockito.times(0)).createNewConfig(
            ArgumentMatchers.any(
                ConfigDto::class.java
            )
        )
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessage(ArgumentMatchers.eq("This is currently reserved for the owner of the server only, this may change in future"))
    }

    @Test
    fun testSetConfig_withOneConfig_createsThatConfig() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val volumeOptionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(
            CommandTest.event.getOption(
                ConfigDto.Configurations.VOLUME.name.lowercase(
                    Locale.getDefault()
                )
            )
        ).thenReturn(volumeOptionMapping)
        Mockito.`when`(volumeOptionMapping.asInt).thenReturn(20)
        Mockito.`when`(volumeOptionMapping.name).thenReturn(ConfigDto.Configurations.VOLUME.name)
        Mockito.`when`(CommandTest.member.isOwner).thenReturn(true)
        Mockito.`when`<List<OptionMapping>>(CommandTest.event.options)
            .thenReturn(listOf(volumeOptionMapping))
        Mockito.`when`(configService.getConfigByName(ConfigDto.Configurations.VOLUME.configValue, "1"))
            .thenReturn(null)

        //Act
        setConfigCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(configService, Mockito.times(1))
            .getConfigByName(ConfigDto.Configurations.VOLUME.configValue, "1")
        Mockito.verify(configService, Mockito.times(1)).createNewConfig(
            ArgumentMatchers.any(
                ConfigDto::class.java
            )
        )
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessageFormat(ArgumentMatchers.eq("Set default volume to '%s'"), ArgumentMatchers.eq(20))
    }

    @Test
    fun testSetConfig_withOneConfig_updatesThatConfig() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val volumeOptionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(
            CommandTest.event.getOption(
                ConfigDto.Configurations.VOLUME.name.lowercase(
                    Locale.getDefault()
                )
            )
        ).thenReturn(volumeOptionMapping)
        Mockito.`when`(volumeOptionMapping.asInt).thenReturn(20)
        Mockito.`when`(volumeOptionMapping.name).thenReturn(ConfigDto.Configurations.VOLUME.name)
        Mockito.`when`(CommandTest.member.isOwner).thenReturn(true)
        Mockito.`when`<List<OptionMapping>>(CommandTest.event.options)
            .thenReturn(listOf(volumeOptionMapping))
        val dbConfig = Mockito.mock(ConfigDto::class.java)
        Mockito.`when`(configService.getConfigByName(ConfigDto.Configurations.VOLUME.configValue, "1"))
            .thenReturn(dbConfig)
        Mockito.`when`(dbConfig.guildId).thenReturn("1")

        //Act
        setConfigCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(configService, Mockito.times(1))
            .getConfigByName(ConfigDto.Configurations.VOLUME.configValue, "1")
        Mockito.verify(configService, Mockito.times(1)).updateConfig(
            ArgumentMatchers.any(
                ConfigDto::class.java
            )
        )
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessageFormat(ArgumentMatchers.eq("Set default volume to '%s'"), ArgumentMatchers.eq(20))
    }

    @Test
    fun testSetConfig_withDeleteDelay_createsThatConfig() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val deleteDelayOptionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(
            CommandTest.event.getOption(
                ConfigDto.Configurations.DELETE_DELAY.name.lowercase(
                    Locale.getDefault()
                )
            )
        ).thenReturn(deleteDelayOptionMapping)
        Mockito.`when`(deleteDelayOptionMapping.asInt).thenReturn(20)
        Mockito.`when`(deleteDelayOptionMapping.name).thenReturn(ConfigDto.Configurations.DELETE_DELAY.name)
        Mockito.`when`(CommandTest.member.isOwner).thenReturn(true)
        Mockito.`when`<List<OptionMapping>>(CommandTest.event.options)
            .thenReturn(listOf(deleteDelayOptionMapping))
        Mockito.`when`(configService.getConfigByName(ConfigDto.Configurations.DELETE_DELAY.configValue, "1"))
            .thenReturn(null)

        //Act
        setConfigCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(configService, Mockito.times(1))
            .getConfigByName(ConfigDto.Configurations.DELETE_DELAY.configValue, "1")
        Mockito.verify(configService, Mockito.times(1)).createNewConfig(
            ArgumentMatchers.any(
                ConfigDto::class.java
            )
        )
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("Set default delete message delay for TobyBot music messages to '%d' seconds"),
            ArgumentMatchers.eq(20)
        )
    }

    @Test
    fun testSetConfig_withMultipleSpecified_createsTwoConfig() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val deleteDelayOptionMapping = Mockito.mock(OptionMapping::class.java)
        val volumeOptionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(
            CommandTest.event.getOption(
                ConfigDto.Configurations.DELETE_DELAY.name.lowercase(
                    Locale.getDefault()
                )
            )
        ).thenReturn(deleteDelayOptionMapping)
        Mockito.`when`(deleteDelayOptionMapping.asInt).thenReturn(20)
        Mockito.`when`(deleteDelayOptionMapping.name).thenReturn(ConfigDto.Configurations.DELETE_DELAY.name)
        Mockito.`when`<OptionMapping>(
            CommandTest.event.getOption(
                ConfigDto.Configurations.VOLUME.name.lowercase(
                    Locale.getDefault()
                )
            )
        ).thenReturn(volumeOptionMapping)
        Mockito.`when`(volumeOptionMapping.asInt).thenReturn(20)
        Mockito.`when`(volumeOptionMapping.name).thenReturn(ConfigDto.Configurations.VOLUME.name)
        Mockito.`when`(CommandTest.member.isOwner).thenReturn(true)
        Mockito.`when`<List<OptionMapping>>(CommandTest.event.options)
            .thenReturn(listOf(deleteDelayOptionMapping, volumeOptionMapping))
        Mockito.`when`(configService.getConfigByName(ConfigDto.Configurations.DELETE_DELAY.name, "1"))
            .thenReturn(null)
        Mockito.`when`(configService.getConfigByName(ConfigDto.Configurations.VOLUME.name, "1")).thenReturn(null)

        //Act
        setConfigCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(configService, Mockito.times(1))
            .getConfigByName(ConfigDto.Configurations.DELETE_DELAY.configValue, "1")
        Mockito.verify(configService, Mockito.times(1))
            .getConfigByName(ConfigDto.Configurations.VOLUME.configValue, "1")
        Mockito.verify(configService, Mockito.times(2)).createNewConfig(
            ArgumentMatchers.any(
                ConfigDto::class.java
            )
        )
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessageFormat(ArgumentMatchers.eq("Set default volume to '%s'"), ArgumentMatchers.eq(20))
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("Set default delete message delay for TobyBot music messages to '%d' seconds"),
            ArgumentMatchers.eq(20)
        )
    }

    @Test
    fun testSetConfig_withMoveChannel_createsThatConfig() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val moveOptionMapping = Mockito.mock(OptionMapping::class.java)
        val guildChannelUnion = Mockito.mock(GuildChannelUnion::class.java)
        Mockito.`when`<OptionMapping>(
            CommandTest.event.getOption(
                ConfigDto.Configurations.MOVE.name.lowercase(
                    Locale.getDefault()
                )
            )
        ).thenReturn(moveOptionMapping)
        Mockito.`when`(moveOptionMapping.asChannel).thenReturn(guildChannelUnion)
        Mockito.`when`(moveOptionMapping.name)
            .thenReturn(ConfigDto.Configurations.MOVE.name.lowercase(Locale.getDefault()))
        Mockito.`when`(guildChannelUnion.name).thenReturn("Channel Name")
        Mockito.`when`(CommandTest.member.isOwner).thenReturn(true)
        Mockito.`when`<List<OptionMapping>>(CommandTest.event.options)
            .thenReturn(listOf(moveOptionMapping))
        Mockito.`when`(configService.getConfigByName(ConfigDto.Configurations.MOVE.configValue, "1")).thenReturn(null)

        //Act
        setConfigCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(configService, Mockito.times(1)).getConfigByName(ConfigDto.Configurations.MOVE.configValue, "1")
        Mockito.verify(configService, Mockito.times(1)).createNewConfig(
            ArgumentMatchers.any(
                ConfigDto::class.java
            )
        )
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("Set default move channel to '%s'"),
            ArgumentMatchers.eq("Channel Name")
        )
    }
}