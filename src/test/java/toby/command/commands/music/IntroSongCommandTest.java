package toby.command.commands.music;

import net.dv8tion.jda.api.entities.Mentions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import toby.command.CommandContext;
import toby.jpa.dto.ConfigDto;
import toby.jpa.service.IConfigService;
import toby.jpa.service.IMusicFileService;
import toby.jpa.service.IUserService;

import java.util.List;

import static org.mockito.Mockito.*;

class IntroSongCommandTest implements MusicCommandTest {

    IntroSongCommand introSongCommand;

    @Mock
    IUserService userService;
    @Mock
    IMusicFileService musicFileService;
    @Mock
    IConfigService configService;

    @BeforeEach
    void setUp() {
        setupCommonMusicMocks();
        userService = mock(IUserService.class);
        musicFileService = mock(IMusicFileService.class);
        configService = mock(IConfigService.class);
        introSongCommand = new IntroSongCommand(userService, musicFileService, configService);
    }

    @AfterEach
    void tearDown() {
        tearDownCommonMusicMocks();
    }

    @Test
    void testIntroSong_withSuperuser_andValidLinkAttached_setsIntroViaUrl() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        OptionMapping linkOptionMapping = mock(OptionMapping.class);
        OptionMapping userOptionMapping = mock(OptionMapping.class);

        when(event.getOption("users")).thenReturn(userOptionMapping);
        when(event.getOption("link")).thenReturn(linkOptionMapping);
        when(linkOptionMapping.getAsString()).thenReturn("https://www.youtube.com/");
        when(userService.listGuildUsers(1L)).thenReturn(List.of(requestingUserDto));
        when(configService.getConfigByName("DEFAULT_VOLUME", "1")).thenReturn(new ConfigDto("DEFAULT_VOLUME", "20", "1"));

        //Act
        introSongCommand.handleMusicCommand(commandContext, playerManager, requestingUserDto, 0);

        //Assert
        verify(interactionHook, timeout(1)).deleteOriginal();
        verify(interactionHook, timeout(1)).sendMessageFormat(eq("Successfully set %s's intro song to '%s' with volume '%d'"), eq("UserName"), eq("https://www.youtube.com/"), eq(20));

    }

    @Test
    void testIntroSong_withoutPermissionsAndSomeoneMentioned_andValidLinkAttached_doesNotSetIntroViaUrl() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        OptionMapping linkOptionMapping = mock(OptionMapping.class);
        OptionMapping userOptionMapping = mock(OptionMapping.class);

        when(event.getOption("users")).thenReturn(userOptionMapping);
        Mentions mentions = mock(Mentions.class);
        when(userOptionMapping.getMentions()).thenReturn(mentions);
        when(mentions.getMembers()).thenReturn(List.of(member));
        when(event.getOption("link")).thenReturn(linkOptionMapping);
        when(linkOptionMapping.getAsString()).thenReturn("https://www.youtube.com/");
        when(userService.listGuildUsers(1L)).thenReturn(List.of(requestingUserDto));
        when(configService.getConfigByName("DEFAULT_VOLUME", "1")).thenReturn(new ConfigDto("DEFAULT_VOLUME", "20", "1"));
        when(guild.getOwner()).thenReturn(member);
        when(member.getEffectiveName()).thenReturn("Effective Name");
        requestingUserDto.setSuperUser(false);

        //Act
        introSongCommand.handleMusicCommand(commandContext, playerManager, requestingUserDto, 0);

        //Assert
        verify(interactionHook, timeout(1)).deleteOriginal();
        verify(interactionHook, timeout(1)).sendMessageFormat(eq("You do not have adequate permissions to use this command, if you believe this is a mistake talk to the server owner: Effective Name"));

    }

}