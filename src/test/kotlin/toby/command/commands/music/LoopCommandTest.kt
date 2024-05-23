package toby.command.commands.music

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.commands.music.MusicCommandTest.Companion.audioPlayer
import toby.command.commands.music.MusicCommandTest.Companion.playerManager
import toby.command.commands.music.MusicCommandTest.Companion.trackScheduler

internal class LoopCommandTest : MusicCommandTest {
    lateinit var loopCommand: LoopCommand

    @BeforeEach
    fun setUp() {
        setupCommonMusicMocks()
        loopCommand = LoopCommand()
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMusicMocks()
    }

    @Test
    fun test_looping_whenNotCurrentlyLooping() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        Mockito.`when`(audioPlayer.isPaused).thenReturn(false)
        Mockito.`when`(playerManager.isCurrentlyStoppable).thenReturn(true)
        Mockito.`when`(trackScheduler.isLooping).thenReturn(false)

        //Act
        loopCommand.handleMusicCommand(
            commandContext,
            playerManager,
            CommandTest.requestingUserDto,
            0
        )

        //Assert
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("The Player has been set to **%s**"),
            ArgumentMatchers.eq("looping")
        )
        Mockito.verify(trackScheduler, Mockito.times(1)).isLooping = true
    }

    @Test
    fun test_looping_whenCurrentlyLooping() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        Mockito.`when`(audioPlayer.isPaused).thenReturn(false)
        Mockito.`when`(playerManager.isCurrentlyStoppable).thenReturn(true)
        Mockito.`when`(trackScheduler.isLooping).thenReturn(true)

        //Act
        loopCommand.handleMusicCommand(
            commandContext,
            playerManager,
            CommandTest.requestingUserDto,
            0
        )

        //Assert
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("The Player has been set to **%s**"),
            ArgumentMatchers.eq("not looping")
        )
        Mockito.verify(trackScheduler, Mockito.times(1)).isLooping = false
    }
}