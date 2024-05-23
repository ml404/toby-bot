package toby.command.commands.music

import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import toby.command.CommandContext
import toby.command.CommandTest
import toby.emote.Emotes


internal class SetVolumeCommandTest : MusicCommandTest {
    lateinit var setVolumeCommand: SetVolumeCommand

    @BeforeEach
    fun setUp() {
        setupCommonMusicMocks()
        setVolumeCommand = SetVolumeCommand()
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMusicMocks()
    }

    @Test
    fun testSetVolume_withValidArgs() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        val volumeOptionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("volume")).thenReturn(volumeOptionMapping)
        val volumeArg = 20
        Mockito.`when`(volumeOptionMapping.asInt).thenReturn(volumeArg)
        Mockito.`when`(MusicCommandTest.playerManager.isCurrentlyStoppable).thenReturn(true)
        val oldVolume = 21
        Mockito.`when`(MusicCommandTest.audioPlayer.volume).thenReturn(oldVolume)

        //Act
        setVolumeCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        //Assert
        Mockito.verify(MusicCommandTest.audioPlayer, Mockito.times(1)).volume = volumeArg
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("Changing volume from '%s' to '%s' \uD83D\uDD0A"),
            ArgumentMatchers.eq(oldVolume),
            ArgumentMatchers.eq(volumeArg)
        )
    }

    @Test
    fun testSetVolume_withOldAndNewVolumeBeingTheSame_SendsErrorMessage() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        val volumeOptionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("volume")).thenReturn(volumeOptionMapping)
        val volumeArg = 20
        Mockito.`when`(volumeOptionMapping.asInt).thenReturn(volumeArg)
        Mockito.`when`(MusicCommandTest.playerManager.isCurrentlyStoppable).thenReturn(true)
        val oldVolume = 20
        Mockito.`when`(MusicCommandTest.audioPlayer.volume).thenReturn(oldVolume)

        //Act
        setVolumeCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        //Assert
        Mockito.verify(MusicCommandTest.audioPlayer, Mockito.times(0)).volume = volumeArg
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("New volume and old volume are the same value, somebody shoot %s"),
            ArgumentMatchers.eq("Effective Name")
        )
    }

    @Test
    fun testSetVolume_withNewVolumeBeingOver100_SendsErrorMessage() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        val volumeOptionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("volume")).thenReturn(volumeOptionMapping)
        val volumeArg = 101
        Mockito.`when`(volumeOptionMapping.asInt).thenReturn(volumeArg)
        Mockito.`when`(MusicCommandTest.playerManager.isCurrentlyStoppable).thenReturn(true)
        val oldVolume = 20
        Mockito.`when`(MusicCommandTest.audioPlayer.volume).thenReturn(oldVolume)

        //Act
        setVolumeCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        //Assert
        Mockito.verify(MusicCommandTest.audioPlayer, Mockito.times(0)).volume = volumeArg
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessage(ArgumentMatchers.eq("Set the volume of the audio player for the server to a percent value (between 1 and 100)"))
    }

    @Test
    fun testSetVolume_withNewVolumeBeingNegative_SendsErrorMessage() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        val volumeOptionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("volume")).thenReturn(volumeOptionMapping)
        val volumeArg = 101
        Mockito.`when`(volumeOptionMapping.asInt).thenReturn(volumeArg)
        Mockito.`when`(MusicCommandTest.playerManager.isCurrentlyStoppable).thenReturn(true)
        val oldVolume = 20
        Mockito.`when`(MusicCommandTest.audioPlayer.volume).thenReturn(oldVolume)

        //Act
        setVolumeCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        //Assert
        Mockito.verify(MusicCommandTest.audioPlayer, Mockito.times(0)).volume = volumeArg
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessage(ArgumentMatchers.eq("Set the volume of the audio player for the server to a percent value (between 1 and 100)"))
    }

    @Test
    fun testSetVolume_withInvalidPermissions() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        val volumeOptionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("volume")).thenReturn(volumeOptionMapping)
        val volumeArg = 20
        Mockito.`when`(volumeOptionMapping.asInt).thenReturn(volumeArg)
        Mockito.`when`(MusicCommandTest.playerManager.isCurrentlyStoppable).thenReturn(true)
        val oldVolume = 21
        Mockito.`when`(MusicCommandTest.audioPlayer.volume).thenReturn(oldVolume)
        Mockito.`when`(CommandTest.requestingUserDto.musicPermission).thenReturn(false)
        val tobyEmote = Mockito.mock(RichCustomEmoji::class.java)
        Mockito.`when`<RichCustomEmoji>(CommandTest.jda.getEmojiById(Emotes.TOBY)).thenReturn(tobyEmote)

        //Act
        setVolumeCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        //Assert
        Mockito.verify(MusicCommandTest.audioPlayer, Mockito.times(0)).volume = volumeArg
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("You aren't allowed to change the volume kid %s"),
            ArgumentMatchers.eq(tobyEmote)
        )
    }

    @Test
    fun testSetVolume_whenSongIsNotStoppableAndWithoutOverridingPermissions_SendsError() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        val volumeOptionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("volume")).thenReturn(volumeOptionMapping)
        val volumeArg = 20
        Mockito.`when`(volumeOptionMapping.asInt).thenReturn(volumeArg)
        Mockito.`when`(MusicCommandTest.playerManager.isCurrentlyStoppable).thenReturn(false)
        val oldVolume = 21
        Mockito.`when`(MusicCommandTest.audioPlayer.volume).thenReturn(oldVolume)
        Mockito.`when`(CommandTest.requestingUserDto.superUser).thenReturn(false)
        val tobyEmote = Mockito.mock(RichCustomEmoji::class.java)
        Mockito.`when`<RichCustomEmoji>(CommandTest.jda.getEmojiById(Emotes.TOBY)).thenReturn(tobyEmote)

        //Act
        setVolumeCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        //Assert
        Mockito.verify(MusicCommandTest.audioPlayer, Mockito.times(0)).volume = volumeArg
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("You aren't allowed to change the volume kid %s"),
            ArgumentMatchers.eq(tobyEmote)
        )
    }
}