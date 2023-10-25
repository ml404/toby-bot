package toby.command.commands.music;

import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import toby.command.CommandContext;
import toby.emote.Emotes;

import static org.mockito.Mockito.*;

class SetVolumeCommandTest implements MusicCommandTest {

    SetVolumeCommand setVolumeCommand;

    @BeforeEach
    void setUp() {
        setupCommonMusicMocks();
        setVolumeCommand = new SetVolumeCommand();
    }

    @AfterEach
    void tearDown() {
        tearDownCommonMusicMocks();
    }

    @Test
    void testSetVolume_withValidArgs() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel();
        CommandContext commandContext = new CommandContext(event);
        OptionMapping volumeOptionMapping = mock(OptionMapping.class);
        when(event.getOption("volume")).thenReturn(volumeOptionMapping);
        int volumeArg = 20;
        when(volumeOptionMapping.getAsInt()).thenReturn(volumeArg);
        when(playerManager.isCurrentlyStoppable()).thenReturn(true);
        int oldVolume = 21;
        when(audioPlayer.getVolume()).thenReturn(oldVolume);

        //Act
        setVolumeCommand.handleMusicCommand(commandContext, playerManager, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).deleteOriginal();
        verify(audioPlayer, times(1)).setVolume(volumeArg);
        verify(interactionHook, times(1)).sendMessageFormat(eq("Changing volume from '%s' to '%s' \uD83D\uDD0A"), eq(oldVolume), eq(volumeArg));
    }

    @Test
    void testSetVolume_withOldAndNewVolumeBeingTheSame_SendsErrorMessage() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel();
        CommandContext commandContext = new CommandContext(event);
        OptionMapping volumeOptionMapping = mock(OptionMapping.class);
        when(event.getOption("volume")).thenReturn(volumeOptionMapping);
        int volumeArg = 20;
        when(volumeOptionMapping.getAsInt()).thenReturn(volumeArg);
        when(playerManager.isCurrentlyStoppable()).thenReturn(true);
        int oldVolume = 20;
        when(audioPlayer.getVolume()).thenReturn(oldVolume);

        //Act
        setVolumeCommand.handleMusicCommand(commandContext, playerManager, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).deleteOriginal();
        verify(audioPlayer, times(0)).setVolume(volumeArg);
        verify(interactionHook, times(1)).sendMessageFormat(eq("New volume and old volume are the same value, somebody shoot %s"), eq("Effective Name"));
    }

    @Test
    void testSetVolume_withNewVolumeBeingOver100_SendsErrorMessage() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel();
        CommandContext commandContext = new CommandContext(event);
        OptionMapping volumeOptionMapping = mock(OptionMapping.class);
        when(event.getOption("volume")).thenReturn(volumeOptionMapping);
        int volumeArg = 101;
        when(volumeOptionMapping.getAsInt()).thenReturn(volumeArg);
        when(playerManager.isCurrentlyStoppable()).thenReturn(true);
        int oldVolume = 20;
        when(audioPlayer.getVolume()).thenReturn(oldVolume);

        //Act
        setVolumeCommand.handleMusicCommand(commandContext, playerManager, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).deleteOriginal();
        verify(audioPlayer, times(0)).setVolume(volumeArg);
        verify(interactionHook, times(1)).sendMessage(eq("Set the volume of the audio player for the server to a percent value (between 1 and 100)"));
    }

    @Test
    void testSetVolume_withNewVolumeBeingNegative_SendsErrorMessage() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel();
        CommandContext commandContext = new CommandContext(event);
        OptionMapping volumeOptionMapping = mock(OptionMapping.class);
        when(event.getOption("volume")).thenReturn(volumeOptionMapping);
        int volumeArg = 101;
        when(volumeOptionMapping.getAsInt()).thenReturn(volumeArg);
        when(playerManager.isCurrentlyStoppable()).thenReturn(true);
        int oldVolume = 20;
        when(audioPlayer.getVolume()).thenReturn(oldVolume);

        //Act
        setVolumeCommand.handleMusicCommand(commandContext, playerManager, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).deleteOriginal();
        verify(audioPlayer, times(0)).setVolume(volumeArg);
        verify(interactionHook, times(1)).sendMessage(eq("Set the volume of the audio player for the server to a percent value (between 1 and 100)"));
    }

    @Test
    void testSetVolume_withInvalidPermissions() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel();
        CommandContext commandContext = new CommandContext(event);
        OptionMapping volumeOptionMapping = mock(OptionMapping.class);
        when(event.getOption("volume")).thenReturn(volumeOptionMapping);
        int volumeArg = 20;
        when(volumeOptionMapping.getAsInt()).thenReturn(volumeArg);
        when(playerManager.isCurrentlyStoppable()).thenReturn(true);
        int oldVolume = 21;
        when(audioPlayer.getVolume()).thenReturn(oldVolume);
        when(requestingUserDto.hasMusicPermission()).thenReturn(false);
        RichCustomEmoji tobyEmote = mock(RichCustomEmoji.class);
        when(jda.getEmojiById(Emotes.TOBY)).thenReturn(tobyEmote);

        //Act
        setVolumeCommand.handleMusicCommand(commandContext, playerManager, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).deleteOriginal();
        verify(audioPlayer, times(0)).setVolume(volumeArg);
        verify(interactionHook, times(1)).sendMessageFormat(eq("You aren't allowed to change the volume kid %s"), eq(tobyEmote));
    }

    @Test
    void testSetVolume_whenSongIsNotStoppableAndWithoutOverridingPermissions_SendsError() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel();
        CommandContext commandContext = new CommandContext(event);
        OptionMapping volumeOptionMapping = mock(OptionMapping.class);
        when(event.getOption("volume")).thenReturn(volumeOptionMapping);
        int volumeArg = 20;
        when(volumeOptionMapping.getAsInt()).thenReturn(volumeArg);
        when(playerManager.isCurrentlyStoppable()).thenReturn(false);
        int oldVolume = 21;
        when(audioPlayer.getVolume()).thenReturn(oldVolume);
        when(requestingUserDto.isSuperUser()).thenReturn(false);
        RichCustomEmoji tobyEmote = mock(RichCustomEmoji.class);
        when(jda.getEmojiById(Emotes.TOBY)).thenReturn(tobyEmote);

        //Act
        setVolumeCommand.handleMusicCommand(commandContext, playerManager, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).deleteOriginal();
        verify(audioPlayer, times(0)).setVolume(volumeArg);
        verify(interactionHook, times(1)).sendMessageFormat(eq("You aren't allowed to change the volume kid %s"), eq(tobyEmote));
    }
}