package toby.command.commands.moderation;

import net.dv8tion.jda.api.entities.channel.unions.GuildChannelUnion;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import toby.command.CommandContext;
import toby.command.commands.CommandTest;
import toby.jpa.dto.ConfigDto;
import toby.jpa.service.IConfigService;

import java.util.List;

import static org.mockito.Mockito.*;
import static toby.jpa.dto.ConfigDto.Configurations.*;

class SetConfigCommandTest implements CommandTest {

    SetConfigCommand setConfigCommand;

    @Mock
    IConfigService configService;

    @BeforeEach
    void setUp() {
        setUpCommonMocks();
        configService = mock(IConfigService.class);
        setConfigCommand = new SetConfigCommand(configService);
    }

    @AfterEach
    void tearDown() {
        tearDownCommonMocks();
        reset(configService);
    }

    @Test
    void testSetConfig_notAsServerOwner_sendsErrorMessage() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        OptionMapping volumeOptionMapping = mock(OptionMapping.class);
        when(event.getOption(VOLUME.getConfigValue())).thenReturn(volumeOptionMapping);
        when(volumeOptionMapping.getAsInt()).thenReturn(20);
        when(volumeOptionMapping.getName()).thenReturn(VOLUME.name());
        when(member.isOwner()).thenReturn(false);
        when(event.getOptions()).thenReturn(List.of(volumeOptionMapping));
        when(configService.getConfigByName(VOLUME.name(), "1")).thenReturn(null);

        //Act
        setConfigCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).deleteOriginal();
        verify(configService, times(0)).getConfigByName(VOLUME.getConfigValue(), "1");
        verify(configService, times(0)).createNewConfig(any(ConfigDto.class));
        verify(interactionHook, times(1)).sendMessage(eq("This is currently reserved for the owner of the server only, this may change in future"));
    }

    @Test
    void testSetConfig_withOneConfig_createsThatConfig() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        OptionMapping volumeOptionMapping = mock(OptionMapping.class);
        when(event.getOption(VOLUME.getConfigValue())).thenReturn(volumeOptionMapping);
        when(volumeOptionMapping.getAsInt()).thenReturn(20);
        when(volumeOptionMapping.getName()).thenReturn(VOLUME.name());
        when(member.isOwner()).thenReturn(true);
        when(event.getOptions()).thenReturn(List.of(volumeOptionMapping));
        when(configService.getConfigByName(VOLUME.getConfigValue(), "1")).thenReturn(null);

        //Act
        setConfigCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).deleteOriginal();
        verify(configService, times(1)).getConfigByName(VOLUME.getConfigValue(), "1");
        verify(configService, times(1)).createNewConfig(any(ConfigDto.class));
        verify(interactionHook, times(1)).sendMessageFormat(eq("Set default volume to '%s'"), eq(20));
    }

    @Test
    void testSetConfig_withOneConfig_updatesThatConfig() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        OptionMapping volumeOptionMapping = mock(OptionMapping.class);
        when(event.getOption(VOLUME.getConfigValue())).thenReturn(volumeOptionMapping);
        when(volumeOptionMapping.getAsInt()).thenReturn(20);
        when(volumeOptionMapping.getName()).thenReturn(VOLUME.name());
        when(member.isOwner()).thenReturn(true);
        when(event.getOptions()).thenReturn(List.of(volumeOptionMapping));
        ConfigDto dbConfig = mock(ConfigDto.class);
        when(configService.getConfigByName(VOLUME.getConfigValue(), "1")).thenReturn(dbConfig);
        when(dbConfig.getGuildId()).thenReturn("1");

        //Act
        setConfigCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).deleteOriginal();
        verify(configService, times(1)).getConfigByName(VOLUME.getConfigValue(), "1");
        verify(configService, times(1)).updateConfig(any(ConfigDto.class));
        verify(interactionHook, times(1)).sendMessageFormat(eq("Set default volume to '%s'"), eq(20));
    }

    @Test
    void testSetConfig_withDeleteDelay_createsThatConfig() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        OptionMapping deleteDelayOptionMapping = mock(OptionMapping.class);
        when(event.getOption(DELETE_DELAY.getConfigValue())).thenReturn(deleteDelayOptionMapping);
        when(deleteDelayOptionMapping.getAsInt()).thenReturn(20);
        when(deleteDelayOptionMapping.getName()).thenReturn(DELETE_DELAY.name());
        when(member.isOwner()).thenReturn(true);
        when(event.getOptions()).thenReturn(List.of(deleteDelayOptionMapping));
        when(configService.getConfigByName(DELETE_DELAY.getConfigValue(), "1")).thenReturn(null);

        //Act
        setConfigCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).deleteOriginal();
        verify(configService, times(1)).getConfigByName(DELETE_DELAY.getConfigValue(), "1");
        verify(configService, times(1)).createNewConfig(any(ConfigDto.class));
        verify(interactionHook, times(1)).sendMessageFormat(eq("Set default delete message delay for TobyBot music messages to '%d' seconds"), eq(20));
    }

    @Test
    void testSetConfig_withMultipleSpecified_createsTwoConfig() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        OptionMapping deleteDelayOptionMapping = mock(OptionMapping.class);
        OptionMapping volumeOptionMapping = mock(OptionMapping.class);
        when(event.getOption(DELETE_DELAY.getConfigValue())).thenReturn(deleteDelayOptionMapping);
        when(deleteDelayOptionMapping.getAsInt()).thenReturn(20);
        when(deleteDelayOptionMapping.getName()).thenReturn(DELETE_DELAY.name());
        when(event.getOption(VOLUME.getConfigValue())).thenReturn(volumeOptionMapping);
        when(volumeOptionMapping.getAsInt()).thenReturn(20);
        when(volumeOptionMapping.getName()).thenReturn(VOLUME.name());
        when(member.isOwner()).thenReturn(true);
        when(event.getOptions()).thenReturn(List.of(deleteDelayOptionMapping, volumeOptionMapping));
        when(configService.getConfigByName(DELETE_DELAY.name(), "1")).thenReturn(null);
        when(configService.getConfigByName(VOLUME.name(), "1")).thenReturn(null);

        //Act
        setConfigCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).deleteOriginal();
        verify(configService, times(1)).getConfigByName(DELETE_DELAY.getConfigValue(), "1");
        verify(configService, times(1)).getConfigByName(VOLUME.getConfigValue(), "1");
        verify(configService, times(2)).createNewConfig(any(ConfigDto.class));
        verify(interactionHook, times(1)).sendMessageFormat(eq("Set default volume to '%s'"), eq(20));
        verify(interactionHook, times(1)).sendMessageFormat(eq("Set default delete message delay for TobyBot music messages to '%d' seconds"), eq(20));
    }

    @Test
    void testSetConfig_withMoveChannel_createsThatConfig() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        OptionMapping moveOptionMapping = mock(OptionMapping.class);
        GuildChannelUnion guildChannelUnion = mock(GuildChannelUnion.class);
        when(event.getOption(MOVE.getConfigValue())).thenReturn(moveOptionMapping);
        when(moveOptionMapping.getAsChannel()).thenReturn(guildChannelUnion);
        when(moveOptionMapping.getName()).thenReturn(MOVE.name());
        when(guildChannelUnion.getName()).thenReturn("Channel Name");
        when(member.isOwner()).thenReturn(true);
        when(event.getOptions()).thenReturn(List.of(moveOptionMapping));
        when(configService.getConfigByName(MOVE.getConfigValue(), "1")).thenReturn(null);

        //Act
        setConfigCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).deleteOriginal();
        verify(configService, times(1)).getConfigByName(MOVE.getConfigValue(), "1");
        verify(configService, times(1)).createNewConfig(any(ConfigDto.class));
        verify(interactionHook, times(1)).sendMessageFormat(eq("Set default move channel to '%s'"), eq("Channel Name"));
    }
}