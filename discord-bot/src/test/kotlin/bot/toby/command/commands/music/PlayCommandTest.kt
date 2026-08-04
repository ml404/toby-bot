package bot.toby.command.commands.music

import bot.toby.command.CommandTest
import bot.toby.command.DefaultCommandContext
import bot.toby.command.commands.music.MusicCommandTest.Companion.mockAudioPlayer
import bot.toby.command.commands.music.MusicCommandTest.Companion.playerManager
import bot.toby.command.commands.music.MusicCommandTest.Companion.track
import bot.toby.command.commands.music.MusicCommandTest.Companion.trackScheduler
import bot.toby.command.commands.music.player.PlayCommand
import bot.toby.helpers.MusicPlayerHelper
import bot.toby.helpers.UserDtoHelper
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import database.dto.music.MusicDto
import database.dto.user.UserDto
import io.mockk.*
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ArrayBlockingQueue

internal class PlayCommandTest : MusicCommandTest {

    private lateinit var playCommand: PlayCommand
    private lateinit var userDtoHelper: UserDtoHelper

    @BeforeEach
    fun setUp() {
        setupCommonMusicMocks()
        userDtoHelper = mockk(relaxed = true)
        playCommand = PlayCommand(userDtoHelper)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMusicMocks()
        clearAllMocks()
    }

    @Test
    fun test_playCommand_linkSubcommand_withValidArguments() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = DefaultCommandContext(CommandTest.event)
        every { mockAudioPlayer.isPaused } returns false
        every { playerManager.isCurrentlyStoppable } returns false

        // Mock event options for subcommand "link"
        val linkOptionMapping = mockk<OptionMapping>()
        val volumeOptionMapping = mockk<OptionMapping>()
        val startOptionMapping = mockk<OptionMapping>()

        every { CommandTest.event.subcommandName } returns "link"
        every { CommandTest.event.getOption("link") } returns linkOptionMapping
        every { CommandTest.event.getOption("volume") } returns volumeOptionMapping
        every { CommandTest.event.getOption("start") } returns startOptionMapping

        every { linkOptionMapping.asString } returns "https://www.testlink.com"
        every { volumeOptionMapping.asInt } returns 20
        every { startOptionMapping.asLong } returns 0L

        val queue = ArrayBlockingQueue<AudioTrack>(2)
        val track2 = mockk<AudioTrack>()
        every { track2.info } returns AudioTrackInfo(
            "Another Title",
            "Another Author",
            1000L,
            "identifier",
            true,
            "uri"
        )
        every { track2.duration } returns 1000L
        queue.add(track)
        queue.add(track2)
        every { trackScheduler.queue } returns queue
        every { track.userData } returns 1

        // Act
        playCommand.handleMusicCommand(
            commandContext,
            playerManager,
            CommandTest.requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 1) {
            playerManager.loadAndPlay(
                eq(CommandTest.guild),
                eq(CommandTest.event),
                eq("https://www.testlink.com"),
                eq(true),
                eq(0),
                eq(0L),
                eq(20)
            )
        }
    }

    @Test
    fun test_playCommand_linkSubcommand_plainQueryGetsYtsearchPrefix() {
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = DefaultCommandContext(CommandTest.event)
        every { mockAudioPlayer.isPaused } returns false
        every { playerManager.isCurrentlyStoppable } returns false

        val linkOptionMapping = mockk<OptionMapping>()
        val volumeOptionMapping = mockk<OptionMapping>()
        val startOptionMapping = mockk<OptionMapping>()

        every { CommandTest.event.subcommandName } returns "link"
        every { CommandTest.event.getOption("link") } returns linkOptionMapping
        every { CommandTest.event.getOption("volume") } returns volumeOptionMapping
        every { CommandTest.event.getOption("start") } returns startOptionMapping

        every { linkOptionMapping.asString } returns "linkin park in the end"
        every { volumeOptionMapping.asInt } returns 20
        every { startOptionMapping.asLong } returns 0L

        every { trackScheduler.queue } returns ArrayBlockingQueue(2)

        playCommand.handleMusicCommand(commandContext, playerManager, CommandTest.requestingUserDto, 0)

        verify(exactly = 1) {
            playerManager.loadAndPlay(
                eq(CommandTest.guild),
                eq(CommandTest.event),
                eq("ytsearch:linkin park in the end"),
                eq(true),
                eq(0),
                eq(0L),
                eq(20)
            )
        }
    }

    @Test
    fun test_playCommand_linkSubcommand_explicitPrefixPassthrough() {
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = DefaultCommandContext(CommandTest.event)
        every { mockAudioPlayer.isPaused } returns false
        every { playerManager.isCurrentlyStoppable } returns false

        val linkOptionMapping = mockk<OptionMapping>()
        val volumeOptionMapping = mockk<OptionMapping>()
        val startOptionMapping = mockk<OptionMapping>()

        every { CommandTest.event.subcommandName } returns "link"
        every { CommandTest.event.getOption("link") } returns linkOptionMapping
        every { CommandTest.event.getOption("volume") } returns volumeOptionMapping
        every { CommandTest.event.getOption("start") } returns startOptionMapping

        every { linkOptionMapping.asString } returns "scsearch:lofi beats"
        every { volumeOptionMapping.asInt } returns 20
        every { startOptionMapping.asLong } returns 0L

        every { trackScheduler.queue } returns ArrayBlockingQueue(2)

        playCommand.handleMusicCommand(commandContext, playerManager, CommandTest.requestingUserDto, 0)

        verify(exactly = 1) {
            playerManager.loadAndPlay(
                eq(CommandTest.guild),
                eq(CommandTest.event),
                eq("scsearch:lofi beats"),
                eq(true),
                eq(0),
                eq(0L),
                eq(20)
            )
        }
    }

    @Test
    fun test_playCommand_linkSubcommand_spotifyUrlPassthrough() {
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = DefaultCommandContext(CommandTest.event)
        every { mockAudioPlayer.isPaused } returns false
        every { playerManager.isCurrentlyStoppable } returns false

        val linkOptionMapping = mockk<OptionMapping>()
        val volumeOptionMapping = mockk<OptionMapping>()
        val startOptionMapping = mockk<OptionMapping>()

        every { CommandTest.event.subcommandName } returns "link"
        every { CommandTest.event.getOption("link") } returns linkOptionMapping
        every { CommandTest.event.getOption("volume") } returns volumeOptionMapping
        every { CommandTest.event.getOption("start") } returns startOptionMapping

        every { linkOptionMapping.asString } returns "https://open.spotify.com/track/4iV5W9uYEdYUVa79Axb7Rh"
        every { volumeOptionMapping.asInt } returns 20
        every { startOptionMapping.asLong } returns 0L

        every { trackScheduler.queue } returns ArrayBlockingQueue(2)

        playCommand.handleMusicCommand(commandContext, playerManager, CommandTest.requestingUserDto, 0)

        verify(exactly = 1) {
            playerManager.loadAndPlay(
                eq(CommandTest.guild),
                eq(CommandTest.event),
                eq("https://open.spotify.com/track/4iV5W9uYEdYUVa79Axb7Rh"),
                eq(true),
                eq(0),
                eq(0L),
                eq(20)
            )
        }
    }

    /** Shared arrangement for the `intro` subcommand. */
    private fun arrangeIntro(userOption: Member? = null) {
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        every { mockAudioPlayer.isPaused } returns false
        every { playerManager.isCurrentlyStoppable } returns false

        every { CommandTest.event.subcommandName } returns "intro"
        every { CommandTest.event.getOption("start") } returns null
        every { CommandTest.event.getOption("volume") } returns null
        every { CommandTest.event.getOption("user") } returns userOption?.let {
            mockk(relaxed = true) { every { asMember } returns it }
        }
        every { CommandTest.event.user } returns CommandTest.user
        every { CommandTest.user.idLong } returns 1L

        mockkObject(MusicPlayerHelper)
        every { MusicPlayerHelper.playUserIntro(any(), any(), any(), any(), any(), any(), any()) } returns null
    }

    private fun withIntros(vararg names: String): UserDto = mockk(relaxed = true) {
        every { musicDtos } returns names.mapIndexed { i, name ->
            MusicDto(this@mockk, i + 1, name, 90, byteArrayOf(1))
        }.toMutableList()
    }

    @Test
    fun test_playCommand_introSubcommand_playsUserIntro() {
        arrangeIntro()
        every { CommandTest.requestingUserDto.musicDtos } returns
            mutableListOf(MusicDto(CommandTest.requestingUserDto, 1, "mine", 90, byteArrayOf(1)))

        playCommand.handleMusicCommand(DefaultCommandContext(CommandTest.event), playerManager, CommandTest.requestingUserDto, 5)

        verify(exactly = 1) {
            MusicPlayerHelper.playUserIntro(
                CommandTest.requestingUserDto,
                CommandTest.guild,
                CommandTest.event,
                5,
                0L,
                CommandTest.member,
                null
            )
        }
        verify(exactly = 0) { playerManager.loadAndPlay(any(), any(), any(), any(), any(), any(), any()) }

        unmockkObject(MusicPlayerHelper)
    }

    @Test
    fun `play intro with a user option previews that member's intro instead`() {
        // Previewing someone else's needs no permission: it is the sound they
        // have already chosen to play to the whole channel on every join.
        val other: Member = mockk(relaxed = true) {
            every { idLong } returns 99L
            every { effectiveName } returns "Someone Else"
            every { guild } returns CommandTest.guild
        }
        arrangeIntro(userOption = other)
        val theirDto = withIntros("theirs")
        every { userDtoHelper.calculateUserDto(99L, 1L) } returns theirDto

        playCommand.handleMusicCommand(DefaultCommandContext(CommandTest.event), playerManager, CommandTest.requestingUserDto, 5)

        verify(exactly = 1) {
            MusicPlayerHelper.playUserIntro(theirDto, CommandTest.guild, CommandTest.event, 5, 0L, other, null)
        }

        unmockkObject(MusicPlayerHelper)
    }

    @Test
    fun `play intro says so when the target has no intro rather than hanging`() {
        // playUserIntro only logs when there's nothing to play, which left the
        // caller staring at a deferred reply that never resolved.
        val other: Member = mockk(relaxed = true) {
            every { idLong } returns 99L
            every { effectiveName } returns "Someone Else"
            every { guild } returns CommandTest.guild
        }
        arrangeIntro(userOption = other)
        every { userDtoHelper.calculateUserDto(99L, 1L) } returns withIntros()

        val replies = mutableListOf<String>()
        every { CommandTest.event.hook.sendMessage(capture(replies)) } returns CommandTest.webhookMessageCreateAction

        playCommand.handleMusicCommand(DefaultCommandContext(CommandTest.event), playerManager, CommandTest.requestingUserDto, 5)

        verify(exactly = 0) { MusicPlayerHelper.playUserIntro(any(), any(), any(), any(), any(), any(), any()) }
        assertTrue(replies.single().contains("Someone Else hasn't set an intro"), replies.single())

        unmockkObject(MusicPlayerHelper)
    }
}
