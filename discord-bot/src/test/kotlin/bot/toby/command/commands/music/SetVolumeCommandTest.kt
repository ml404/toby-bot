package bot.toby.command.commands.music

import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import bot.toby.command.CommandContext
import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.commands.music.player.SetVolumeCommand
import bot.toby.emote.Emotes

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
        clearAllMocks()
    }

    @Test
    fun testSetVolume_withValidArgs() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(event)
        val volumeOptionMapping = mockk<OptionMapping>()
        every { event.getOption("volume") } returns volumeOptionMapping
        val volumeArg = 20
        every { volumeOptionMapping.asInt } returns volumeArg
        every { MusicCommandTest.playerManager.isCurrentlyStoppable } returns true
        val oldVolume = 21
        every { MusicCommandTest.mockAudioPlayer.volume } returns oldVolume

        // Act
        setVolumeCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 1) { MusicCommandTest.mockAudioPlayer.volume = volumeArg }
        verify(exactly = 1) {
            event.hook.sendMessageFormat(
                "Changing volume from '%s' to '%s' \uD83D\uDD0A",
                oldVolume,
                volumeArg
            )
        }
    }

    @Test
    fun testSetVolume_withOldAndNewVolumeBeingTheSame_SendsErrorMessage() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(event)
        val volumeOptionMapping = mockk<OptionMapping>()
        every { event.getOption("volume") } returns volumeOptionMapping
        val volumeArg = 20
        every { volumeOptionMapping.asInt } returns volumeArg
        every { MusicCommandTest.playerManager.isCurrentlyStoppable } returns true
        val oldVolume = 20
        every { MusicCommandTest.mockAudioPlayer.volume } returns oldVolume

        // Act
        setVolumeCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 0) { MusicCommandTest.mockAudioPlayer.volume = volumeArg }
        verify(exactly = 1) {
            event.hook.sendMessageFormat(
                "New volume and old volume are the same value, somebody shoot %s",
                "Effective Name"
            )
        }
    }

    @Test
    fun testSetVolume_withNewVolumeBeingOver100_SendsErrorMessage() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(event)
        val volumeOptionMapping = mockk<OptionMapping>()
        every { event.getOption("volume") } returns volumeOptionMapping
        val volumeArg = 101
        every { volumeOptionMapping.asInt } returns volumeArg
        every { MusicCommandTest.playerManager.isCurrentlyStoppable } returns true
        val oldVolume = 20
        every { MusicCommandTest.mockAudioPlayer.volume } returns oldVolume

        // Act
        setVolumeCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 0) { MusicCommandTest.mockAudioPlayer.volume = volumeArg }
        verify(exactly = 1) {
            event.hook.sendMessage(
                "Set the volume of the audio player for the server to a percent value (between 1 and 100)"
            )
        }
    }

    @Test
    fun testSetVolume_withNewVolumeBeingNegative_SendsErrorMessage() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(event)
        val volumeOptionMapping = mockk<OptionMapping>()
        every { event.getOption("volume") } returns volumeOptionMapping
        val volumeArg = -1
        every { volumeOptionMapping.asInt } returns volumeArg
        every { MusicCommandTest.playerManager.isCurrentlyStoppable } returns true
        val oldVolume = 20
        every { MusicCommandTest.mockAudioPlayer.volume } returns oldVolume

        // Act
        setVolumeCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 0) { MusicCommandTest.mockAudioPlayer.volume = volumeArg }
        verify(exactly = 1) {
            event.hook.sendMessage(
                "Set the volume of the audio player for the server to a percent value (between 1 and 100)"
            )
        }
    }

    @Test
    fun testSetVolume_withInvalidPermissions() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(event)
        val volumeOptionMapping = mockk<OptionMapping>()
        every { event.getOption("volume") } returns volumeOptionMapping
        val volumeArg = 20
        every { volumeOptionMapping.asInt } returns volumeArg
        every { MusicCommandTest.playerManager.isCurrentlyStoppable } returns true
        val oldVolume = 21
        every { MusicCommandTest.mockAudioPlayer.volume } returns oldVolume
        every { CommandTest.requestingUserDto.musicPermission } returns false
        val tobyEmote = mockk<RichCustomEmoji>()
        every { CommandTest.jda.getEmojiById(Emotes.TOBY) } returns tobyEmote

        // Act
        setVolumeCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 0) { MusicCommandTest.mockAudioPlayer.volume = volumeArg }
        verify(exactly = 1) {
            event.hook.sendMessageFormat(
                "You aren't allowed to change the volume kid %s",
                tobyEmote
            )
        }
    }

    @Test
    fun testSetVolume_whenSongIsNotStoppableAndWithoutOverridingPermissions_SendsError() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(event)
        val volumeOptionMapping = mockk<OptionMapping>()
        every { event.getOption("volume") } returns volumeOptionMapping
        val volumeArg = 20
        every { volumeOptionMapping.asInt } returns volumeArg
        every { MusicCommandTest.playerManager.isCurrentlyStoppable } returns false
        val oldVolume = 21
        every { MusicCommandTest.mockAudioPlayer.volume } returns oldVolume
        every { CommandTest.requestingUserDto.superUser } returns false
        val tobyEmote = mockk<RichCustomEmoji>()
        every { CommandTest.jda.getEmojiById(Emotes.TOBY) } returns tobyEmote
        val hook = event.hook

        // Act
        setVolumeCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 0) { MusicCommandTest.mockAudioPlayer.volume = volumeArg }
        verify(exactly = 1) {
            hook.sendMessageFormat(
                "You aren't allowed to change the volume kid %s",
                tobyEmote
            )
        }
    }
}
