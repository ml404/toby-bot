package toby.command.commands.music

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import io.mockk.*
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.CommandTest.Companion.event
import toby.command.commands.music.MusicCommandTest.Companion.trackScheduler
import java.awt.Color
import java.util.concurrent.ArrayBlockingQueue

internal class SkipCommandTest : MusicCommandTest {
    lateinit var skipCommand: SkipCommand

    @BeforeEach
    fun setUp() {
        setupCommonMusicMocks()
        skipCommand = SkipCommand()
        every { event.deferReply(true) } returns CommandTest.replyCallbackAction
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMusicMocks()
        clearAllMocks()
    }

    @Test
    fun test_skipCommand_withValidQueueAndSetup() {
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(event)
        every { MusicCommandTest.mockAudioPlayer.isPaused } returns false
        every { MusicCommandTest.playerManager.isCurrentlyStoppable } returns false
        every { event.getOption("skip") } returns mockk<OptionMapping> {
            every { asInt } returns 1
        }
        val queue: ArrayBlockingQueue<AudioTrack> = ArrayBlockingQueue(2)
        val track2 = mockAudioTrack("Another Title", "Another Author", "identifier", 1000L, true, "uri")
        queue.add(MusicCommandTest.track)
        queue.add(track2)
        every { trackScheduler.queue } returns queue
        every { MusicCommandTest.track.userData } returns 1

        // Capture slot for MessageEmbed
        val embedSlot = slot<MessageEmbed>()

        // Act
        skipCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 1) { trackScheduler.isLooping = false }
        verify(exactly = 1) { trackScheduler.nextTrack() }
        verify(exactly = 1) {
            event.hook.sendMessageEmbeds(capture(embedSlot))
        }

        // Verify properties of captured MessageEmbed
        val messageEmbed = embedSlot.captured
        assert(messageEmbed.title == "Tracks Skipped")
        assert(messageEmbed.description == "Skipped 1 track(s)")
        assert(messageEmbed.color == Color.CYAN)
    }

    @Test
    fun test_skipCommandForMultipleTracks_withValidQueueAndSetup() {
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(event)
        val hook = event.hook
        every { MusicCommandTest.mockAudioPlayer.isPaused } returns false
        every { MusicCommandTest.playerManager.isCurrentlyStoppable } returns true
        val optionMapping = mockk<OptionMapping>()
        every { event.getOption("skip") } returns optionMapping
        every { optionMapping.asInt } returns 2
        val queue: ArrayBlockingQueue<AudioTrack> = ArrayBlockingQueue(3)
        val track2 = mockAudioTrack("Another Title", "Another Author", "identifier", 1000L, true, "uri")
        val track3 = mockAudioTrack("Another Title 1", "Another Author 1", "identifier", 1000L, true, "uri")
        queue.add(MusicCommandTest.track)
        queue.add(track2)
        queue.add(track3)
        every { trackScheduler.queue } returns queue
        every { MusicCommandTest.track.userData } returns 1

        // Capture slot for MessageEmbed
        val embedSlot = slot<MessageEmbed>()

        // Act
        skipCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 1) { trackScheduler.isLooping = false }
        verify(exactly = 2) { trackScheduler.nextTrack() }
        verify(exactly = 1) {
            hook.sendMessageEmbeds(capture(embedSlot))
        }

        // Verify properties of captured MessageEmbed
        val messageEmbed = embedSlot.captured
        assert(messageEmbed.title == "Tracks Skipped")
        assert(messageEmbed.description == "Skipped 2 track(s)")
        assert(messageEmbed.color == Color.CYAN)
    }

    @Test
    fun test_skipCommandWithInvalidAmountOfTracksToSkip_withValidQueueAndSetup() {
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(event)
        every { MusicCommandTest.mockAudioPlayer.isPaused } returns false
        every { MusicCommandTest.playerManager.isCurrentlyStoppable } returns false
        val optionMapping = mockk<OptionMapping>()
        every { event.getOption("skip") } returns optionMapping
        every { optionMapping.asInt } returns -1
        val queue: ArrayBlockingQueue<AudioTrack> = ArrayBlockingQueue(2)
        val track2 = mockAudioTrack("Another Title", "Another Author", "identifier", 1000L, true, "uri")
        queue.add(MusicCommandTest.track)
        queue.add(track2)
        every { trackScheduler.queue } returns queue
        every { MusicCommandTest.track.userData } returns 1

        // Capture slot for MessageEmbed
        val embedSlot = slot<MessageEmbed>()

        // Act
        skipCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 0) { trackScheduler.isLooping = false }
        verify(exactly = 0) { trackScheduler.nextTrack() }
        verify(exactly = 1) {
            event.hook.sendMessageEmbeds(capture(embedSlot))
        }

        // Verify properties of captured MessageEmbed
        val messageEmbed = embedSlot.captured
        assert(messageEmbed.title == "Invalid Skip Request")
        assert(messageEmbed.description == "You're not too bright, but thanks for trying")
        assert(messageEmbed.color == Color.RED)
    }

    @Test
    fun test_skipCommandWithValidNumberOfTracksToSkip_withNoQueueAndSetup() {
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(event)
        every { MusicCommandTest.mockAudioPlayer.isPaused } returns false
        every { MusicCommandTest.playerManager.isCurrentlyStoppable } returns false
        val optionMapping = mockk<OptionMapping>()
        every { event.getOption("skip") } returns optionMapping
        every { optionMapping.asInt } returns 1
        every { trackScheduler.queue } returns ArrayBlockingQueue(1)
        every { MusicCommandTest.track.userData } returns 1
        every { MusicCommandTest.mockAudioPlayer.playingTrack } returns null

        // Capture slot for MessageEmbed
        val embedSlot = slot<MessageEmbed>()

        // Act
        skipCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 0) { trackScheduler.isLooping = false }
        verify(exactly = 0) { trackScheduler.nextTrack() }
        verify(exactly = 1) {
            event.hook.sendMessageEmbeds(capture(embedSlot))
        }

        // Verify properties of captured MessageEmbed
        val messageEmbed = embedSlot.captured
        assert(messageEmbed.title == "No Track Playing")
        assert(messageEmbed.description == "There is no track playing currently")
        assert(messageEmbed.color == Color.RED)
    }

    companion object {
        private fun mockAudioTrack(
            title: String,
            author: String,
            identifier: String,
            songLength: Long,
            isStream: Boolean,
            uri: String
        ): AudioTrack {
            val track2 = mockk<AudioTrack>()
            every { track2.info } returns AudioTrackInfo(title, author, songLength, identifier, isStream, uri)
            every { track2.duration } returns songLength
            return track2
        }
    }
}
