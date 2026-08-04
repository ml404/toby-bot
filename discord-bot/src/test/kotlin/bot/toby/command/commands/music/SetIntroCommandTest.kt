package bot.toby.command.commands.music

import bot.coroutines.MainCoroutineExtension
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.CommandTest.Companion.member
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.DefaultCommandContext
import bot.toby.command.commands.music.intro.SetIntroCommand
import bot.toby.helpers.HttpHelper
import bot.toby.helpers.IntroHelper
import bot.toby.helpers.UserDtoHelper
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import database.dto.guild.ConfigDto
import database.dto.music.MusicDto
import net.dv8tion.jda.api.entities.MessageEmbed
import database.dto.user.UserDto
import database.service.guild.ConfigService
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Mentions
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainCoroutineExtension::class)
internal class SetIntroCommandTest : MusicCommandTest {
    private lateinit var setIntroCommand: SetIntroCommand
    private var userDtoHelper: UserDtoHelper = mockk(relaxed = true)
    private var musicFileService: database.service.music.MusicFileService = mockk(relaxed = true)
    private var configService: ConfigService = mockk(relaxed = true)
    private var httpHelper: HttpHelper = mockk(relaxed = true)
    private lateinit var mentionedUserDto: UserDto

    private fun mockSub(sub: String) {
        every { event.subcommandName } returns sub
    }

    @BeforeEach
    fun setUp() {
        setupCommonMusicMocks()
        mentionedUserDto = mockk(relaxed = true) {
            every { musicDtos } returns emptyList<MusicDto>().toMutableList()
        }
        configService = mockk(relaxed = true)

        every { event.getOption("volume") } returns mockk { every { asInt } returns 20 }
        every { event.getOption("link") } returns mockk { every { asString } returns "https://www.youtube.com/" }
        every { event.getOption("users")?.mentions } returns mockk { every { members } returns emptyList() }
        coEvery { httpHelper.getYouTubeVideoDuration(any()) } returns 15.seconds
        coEvery { httpHelper.getYouTubeVideoTitle(any()) } returns null
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMusicMocks()
        clearAllMocks()
    }

    @Test
    fun testIntroSong_withSuperuser_andValidLinkAttached_setsIntroViaUrl() = runTest {
        mockSub("link")

        val dispatcher = StandardTestDispatcher(testScheduler)
        val introHelper = IntroHelper(userDtoHelper, musicFileService, configService, httpHelper, dispatcher)
        setIntroCommand = SetIntroCommand(introHelper)

        val commandContext = DefaultCommandContext(event)
        val volumeConfig = ConfigDto("DEFAULT_VOLUME", "20", "1")

        every { event.getOption("attachment") } returns mockk(relaxed = true)
        every { configService.getConfigByName("DEFAULT_VOLUME", "1") } returns volumeConfig

        setIntroCommand.handleMusicCommand(commandContext, MusicCommandTest.playerManager, requestingUserDto, 0)
        advanceUntilIdle()

        verify { musicFileService.createNewMusicFile(ofType()) }
        verify { event.hook.sendMessage("Successfully set UserName's intro song #1 to 'https://www.youtube.com/' with volume '20'") }
    }

    @Test
    fun testIntroSong_withSuperuser_andValidLinkAttached_rejectsIntroIfTooLong() = runTest {
        mockSub("link")

        val dispatcher = StandardTestDispatcher(testScheduler)
        val introHelper = IntroHelper(userDtoHelper, musicFileService, configService, httpHelper, dispatcher)
        setIntroCommand = SetIntroCommand(introHelper)

        val commandContext = DefaultCommandContext(event)

        every { event.getOption("attachment") } returns mockk(relaxed = true)
        every { configService.getConfigByName("DEFAULT_VOLUME", "1") } returns ConfigDto("DEFAULT_VOLUME", "20", "1")
        coEvery { httpHelper.getYouTubeVideoDuration(any()) } returns 21.seconds

        setIntroCommand.handleMusicCommand(commandContext, MusicCommandTest.playerManager, requestingUserDto, 0)
        advanceUntilIdle()

        verify(exactly = 0) { musicFileService.createNewMusicFile(any()) }
        verify {
            event.hook.sendMessage(
                "Video is too long (21s). Max allowed is 15s — set a start/end clip to use a longer source."
            )
        }
    }

    @Test
    fun testIntroSong_withSuperuser_andValidLinkAttachedWithExistingMusicFile_createsSecondIntroViaUrl() = runTest {
        mockSub("link")

        val dispatcher = StandardTestDispatcher(testScheduler)
        val introHelper = IntroHelper(userDtoHelper, musicFileService, configService, httpHelper, dispatcher)
        setIntroCommand = SetIntroCommand(introHelper)

        val commandContext = DefaultCommandContext(event)
        val attachmentOptionMapping = mockk<OptionMapping>()

        every { userDtoHelper.calculateUserDto(1L, 1L) } returns requestingUserDto
        every { configService.getConfigByName("DEFAULT_VOLUME", "1") } returns ConfigDto("DEFAULT_VOLUME", "20", "1")
        every { requestingUserDto.musicDtos } returns mutableListOf(
            MusicDto(UserDto(1, 1), 1, "filename", 20, null)
        )

        every { event.getOption("attachment") } returns attachmentOptionMapping
        setupAttachments(attachmentOptionMapping)

        setIntroCommand.handleMusicCommand(commandContext, MusicCommandTest.playerManager, requestingUserDto, 0)
        advanceUntilIdle()

        verify { musicFileService.createNewMusicFile(ofType()) }
        verify { event.hook.sendMessage("Successfully set UserName's intro song #2 to 'https://www.youtube.com/' with volume '20'") }
    }

    @Test
    fun testIntroSong_withSuperuser_andMentionedMembers_setsMentionedMembersIntroViaUrl() = runTest {
        mockSub("link")

        val dispatcher = StandardTestDispatcher(testScheduler)
        val introHelper = IntroHelper(userDtoHelper, musicFileService, configService, httpHelper, dispatcher)
        setIntroCommand = SetIntroCommand(introHelper)

        val commandContext = DefaultCommandContext(event)
        val userOptionMapping = mockk<OptionMapping>()

        every { event.getOption("attachment") } returns mockk(relaxed = true)
        every { event.getOption("users") } returns userOptionMapping
        every { userDtoHelper.calculateUserDto(0L, 1L) } returns requestingUserDto
        every { configService.getConfigByName("DEFAULT_VOLUME", "1") } returns mockk(relaxed = true)

        setupMentions(userOptionMapping)

        setIntroCommand.handleMusicCommand(commandContext, MusicCommandTest.playerManager, requestingUserDto, 0)
        advanceUntilIdle()

        verify { musicFileService.createNewMusicFile(ofType()) }
        verify { event.hook.sendMessage("Successfully set Another Username's intro song #1 to 'https://www.youtube.com/' with volume '20'") }
    }

    @Test
    fun testIntroSong_withoutPermissionsAndSomeoneMentioned_andValidLinkAttached_doesNotSetIntroViaUrl() = runTest {
        mockSub("link")

        val dispatcher = StandardTestDispatcher(testScheduler)
        val introHelper = IntroHelper(userDtoHelper, musicFileService, configService, httpHelper, dispatcher)
        setIntroCommand = SetIntroCommand(introHelper)

        val commandContext = DefaultCommandContext(event)
        val userOptionMapping = mockk<OptionMapping>()
        val mentions = mockk<Mentions>()

        every { event.getOption("users") } returns userOptionMapping
        every { userOptionMapping.mentions } returns mentions
        every { mentions.members } returns listOf(member)

        every { configService.getConfigByName("DEFAULT_VOLUME", "1") } returns ConfigDto("DEFAULT_VOLUME", "20", "1")
        every { guild.owner } returns member
        every { member.effectiveName } returns "Effective Name"
        every { requestingUserDto.superUser } returns false

        setIntroCommand.handleMusicCommand(commandContext, MusicCommandTest.playerManager, requestingUserDto, 0)
        advanceUntilIdle()

        verify {
            event.hook.sendMessageFormat(
                eq("You do not have adequate permissions to use this command, if you believe this is a mistake talk to Effective Name")
            )
        }
    }

    @Test
    fun testIntroSong_withSuperuser_andValidAttachment_setsIntroViaAttachment_andCreatesNewMusicFile() = runTest {
        mockSub("attachment")

        val dispatcher = StandardTestDispatcher(testScheduler)
        val introHelper = IntroHelper(userDtoHelper, musicFileService, configService, httpHelper, dispatcher)
        setIntroCommand = SetIntroCommand(introHelper)

        val commandContext = DefaultCommandContext(event)
        val attachmentOptionMapping = mockk<OptionMapping>()

        every { event.getOption("attachment") } returns attachmentOptionMapping
        setupAttachments(attachmentOptionMapping)

        every { configService.getConfigByName("DEFAULT_VOLUME", "1") } returns ConfigDto("DEFAULT_VOLUME", "20", "1")
        every { event.getOption("link") } returns mockk { every { asString } returns "" }

        setIntroCommand.handleMusicCommand(commandContext, MusicCommandTest.playerManager, requestingUserDto, 0)
        advanceUntilIdle()

        verify { musicFileService.createNewMusicFile(any()) }
        verify {
            event.hook.sendMessage("Successfully set UserName's intro song #1 to 'filename' with volume '20'")
        }
    }

    @Test
    fun testIntroSong_withSuperuser_andMentionedMembers_setsIntroViaAttachment() = runTest {
        mockSub("attachment")

        val dispatcher = StandardTestDispatcher(testScheduler)
        val introHelper = IntroHelper(userDtoHelper, musicFileService, configService, httpHelper, dispatcher)
        setIntroCommand = SetIntroCommand(introHelper)

        val commandContext = DefaultCommandContext(event)
        val attachmentOptionMapping = mockk<OptionMapping>()
        val userOptionMapping = mockk<OptionMapping>()

        every { event.getOption("users") } returns userOptionMapping
        every { event.getOption("attachment") } returns attachmentOptionMapping
        setupAttachments(attachmentOptionMapping)

        every { userDtoHelper.calculateUserDto(0L, 1L) } returns requestingUserDto
        every { configService.getConfigByName("DEFAULT_VOLUME", "1") } returns ConfigDto("DEFAULT_VOLUME", "20", "1")
        every { event.getOption("link") } returns mockk { every { asString } returns "" }

        setupMentions(userOptionMapping)

        setIntroCommand.handleMusicCommand(commandContext, MusicCommandTest.playerManager, requestingUserDto, 0)
        advanceUntilIdle()

        verify { musicFileService.createNewMusicFile(ofType()) }
        verify {
            event.hook.sendMessage("Successfully set Another Username's intro song #1 to 'filename' with volume '20'")
        }
    }

    @Test
    fun testIntroSong_withSuperuser_andValidLinkAttachedWithExistingMusicFiles_doesNotCreateMusicFileWhenAtLimit() =
        runTest {
            mockSub("link")

            val dispatcher = StandardTestDispatcher(testScheduler)
            val introHelper = IntroHelper(userDtoHelper, musicFileService, configService, httpHelper, dispatcher)
            setIntroCommand = SetIntroCommand(introHelper)

            val commandContext = DefaultCommandContext(event)
            val attachmentOptionMapping = mockk<OptionMapping>()

            every { configService.getConfigByName("DEFAULT_VOLUME", "1") } returns ConfigDto("DEFAULT_VOLUME", "20", "1")
            every { requestingUserDto.musicDtos } returns mutableListOf(
                MusicDto(UserDto(1, 1), 1, "filename1", 20, null),
                MusicDto(UserDto(1, 1), 2, "filename2", 20, null),
                MusicDto(UserDto(1, 1), 3, "filename3", 20, null)
            )

            every { event.getOption("attachment") } returns attachmentOptionMapping
            setupAttachments(attachmentOptionMapping)

            setIntroCommand.handleMusicCommand(commandContext, MusicCommandTest.playerManager, requestingUserDto, 0)
            advanceUntilIdle()

            verify(exactly = 0) { musicFileService.createNewMusicFile(any()) }
            verify {
                event.hook.sendMessageEmbeds(match<MessageEmbed> { it.fields.size == 3 })
            }
        }


    // --- clip options (`start` / `end`) -------------------------------------

    private fun mockClipOptions(start: String?, end: String?) {
        every { event.getOption("start") } returns start?.let { v -> mockk { every { asString } returns v } }
        every { event.getOption("end") } returns end?.let { v -> mockk { every { asString } returns v } }
    }

    private fun newCommand(dispatcher: kotlinx.coroutines.CoroutineDispatcher): SetIntroCommand {
        val introHelper =
            IntroHelper(userDtoHelper, musicFileService, configService, httpHelper, dispatcher)
        return SetIntroCommand(introHelper)
    }

    @Test
    fun `a clip inside a long source is accepted and persisted`() = runTest {
        mockSub("link")
        setIntroCommand = newCommand(StandardTestDispatcher(testScheduler))

        every { event.getOption("attachment") } returns mockk(relaxed = true)
        every { configService.getConfigByName("DEFAULT_VOLUME", "1") } returns ConfigDto("DEFAULT_VOLUME", "20", "1")
        // A minute-long video: rejected outright before clips existed.
        coEvery { httpHelper.getYouTubeVideoDuration(any()) } returns 60.seconds
        mockClipOptions("0:10", "0:22")

        setIntroCommand.handleMusicCommand(
            DefaultCommandContext(event), MusicCommandTest.playerManager, requestingUserDto, 0
        )
        advanceUntilIdle()

        val saved = slot<MusicDto>()
        verify { musicFileService.createNewMusicFile(capture(saved)) }
        assertEquals(10_000, saved.captured.startMs)
        assertEquals(22_000, saved.captured.endMs)
        verify {
            event.hook.sendMessage(
                match<String> { it.contains("(clip 0:10 – 0:22)") }
            )
        }
    }

    @Test
    fun `raw seconds are accepted for the clip bounds`() = runTest {
        mockSub("link")
        setIntroCommand = newCommand(StandardTestDispatcher(testScheduler))

        every { event.getOption("attachment") } returns mockk(relaxed = true)
        every { configService.getConfigByName("DEFAULT_VOLUME", "1") } returns ConfigDto("DEFAULT_VOLUME", "20", "1")
        coEvery { httpHelper.getYouTubeVideoDuration(any()) } returns 60.seconds
        mockClipOptions("3", "9")

        setIntroCommand.handleMusicCommand(
            DefaultCommandContext(event), MusicCommandTest.playerManager, requestingUserDto, 0
        )
        advanceUntilIdle()

        val saved = slot<MusicDto>()
        verify { musicFileService.createNewMusicFile(capture(saved)) }
        assertEquals(3_000, saved.captured.startMs)
        assertEquals(9_000, saved.captured.endMs)
    }

    @Test
    fun `a clip longer than the cap is rejected`() = runTest {
        mockSub("link")
        setIntroCommand = newCommand(StandardTestDispatcher(testScheduler))

        every { event.getOption("attachment") } returns mockk(relaxed = true)
        every { configService.getConfigByName("DEFAULT_VOLUME", "1") } returns ConfigDto("DEFAULT_VOLUME", "20", "1")
        coEvery { httpHelper.getYouTubeVideoDuration(any()) } returns 300.seconds
        mockClipOptions("0:10", "1:10")

        setIntroCommand.handleMusicCommand(
            DefaultCommandContext(event), MusicCommandTest.playerManager, requestingUserDto, 0
        )
        advanceUntilIdle()

        verify(exactly = 0) { musicFileService.createNewMusicFile(any()) }
        verify { event.hook.sendMessage(match<String> { it.contains("Clip is too long") }) }
    }

    @Test
    fun `a clip that runs past the end of the source is rejected`() = runTest {
        mockSub("link")
        setIntroCommand = newCommand(StandardTestDispatcher(testScheduler))

        every { event.getOption("attachment") } returns mockk(relaxed = true)
        every { configService.getConfigByName("DEFAULT_VOLUME", "1") } returns ConfigDto("DEFAULT_VOLUME", "20", "1")
        coEvery { httpHelper.getYouTubeVideoDuration(any()) } returns 20.seconds
        mockClipOptions("0:15", "0:25")

        setIntroCommand.handleMusicCommand(
            DefaultCommandContext(event), MusicCommandTest.playerManager, requestingUserDto, 0
        )
        advanceUntilIdle()

        verify(exactly = 0) { musicFileService.createNewMusicFile(any()) }
        verify { event.hook.sendMessage("End time exceeds the source duration.") }
    }

    @Test
    fun `an unparseable timestamp is reported without touching the source`() = runTest {
        mockSub("link")
        setIntroCommand = newCommand(StandardTestDispatcher(testScheduler))

        every { event.getOption("attachment") } returns mockk(relaxed = true)
        every { configService.getConfigByName("DEFAULT_VOLUME", "1") } returns ConfigDto("DEFAULT_VOLUME", "20", "1")
        mockClipOptions("banana", null)

        setIntroCommand.handleMusicCommand(
            DefaultCommandContext(event), MusicCommandTest.playerManager, requestingUserDto, 0
        )
        advanceUntilIdle()

        verify(exactly = 0) { musicFileService.createNewMusicFile(any()) }
        coVerify(exactly = 0) { httpHelper.getYouTubeVideoDuration(any()) }
        verify { event.hook.sendMessage(match<String> { it.startsWith("Clip start") }) }
    }

    @Test
    fun `an uploaded file can be clipped even though its duration is unknown`() = runTest {
        mockSub("attachment")
        setIntroCommand = newCommand(StandardTestDispatcher(testScheduler))

        val attachmentOptionMapping = mockk<OptionMapping>()
        every { event.getOption("attachment") } returns attachmentOptionMapping
        setupAttachments(attachmentOptionMapping)
        every { event.getOption("link") } returns mockk { every { asString } returns "" }
        every { configService.getConfigByName("DEFAULT_VOLUME", "1") } returns ConfigDto("DEFAULT_VOLUME", "20", "1")
        mockClipOptions("0:01", "0:05")

        setIntroCommand.handleMusicCommand(
            DefaultCommandContext(event), MusicCommandTest.playerManager, requestingUserDto, 0
        )
        advanceUntilIdle()

        val saved = slot<MusicDto>()
        verify { musicFileService.createNewMusicFile(capture(saved)) }
        assertEquals(1_000, saved.captured.startMs)
        assertEquals(5_000, saved.captured.endMs)
        // No YouTube lookup for an upload.
        coVerify(exactly = 0) { httpHelper.getYouTubeVideoDuration(any()) }
    }

    // --- `current`: take what is playing right now ---------------------------

    /** A loaded track for the guild player to be sitting on. */
    private fun playingTrack(
        title: String = "Great Song",
        uri: String? = "https://www.youtube.com/watch?v=abc",
        durationMs: Long = 240_000,
        positionMs: Long = 100_000,
        isStream: Boolean = false,
    ) = mockk<com.sedmelluq.discord.lavaplayer.track.AudioTrack>(relaxed = true).also {
        every { it.info } returns AudioTrackInfo(title, "Author", durationMs, "id", isStream, uri)
        every { it.duration } returns durationMs
        every { it.position } returns positionMs
    }

    private fun arrangeCurrent(track: com.sedmelluq.discord.lavaplayer.track.AudioTrack?, isIntro: Boolean = false) {
        mockSub("current")
        every { event.getOption("link") } returns null
        every { event.getOption("attachment") } returns null
        every { configService.getConfigByName("DEFAULT_VOLUME", "1") } returns ConfigDto("DEFAULT_VOLUME", "20", "1")
        every { MusicCommandTest.trackScheduler.currentTrack() } returns track
        every { MusicCommandTest.trackScheduler.isIntroTrack(any()) } returns isIntro
    }

    @Test
    fun `current captures the fifteen seconds behind the playhead`() = runTest {
        // You reach for the command *after* hearing the bit you liked, so the
        // window looks backwards. Forwards would save the wrong fifteen seconds.
        setIntroCommand = newCommand(StandardTestDispatcher(testScheduler))
        arrangeCurrent(playingTrack())
        mockClipOptions(null, null)

        setIntroCommand.handleMusicCommand(
            DefaultCommandContext(event), MusicCommandTest.playerManager, requestingUserDto, 0
        )
        advanceUntilIdle()

        val saved = slot<MusicDto>()
        verify { musicFileService.createNewMusicFile(capture(saved)) }
        assertEquals(85_000, saved.captured.startMs)
        assertEquals(100_000, saved.captured.endMs)
        assertEquals("https://www.youtube.com/watch?v=abc", String(saved.captured.musicBlob!!))
    }

    @Test
    fun `current names the intro from the loaded track instead of looking it up`() = runTest {
        // The title is already in hand, and the lookup only ever worked for
        // YouTube anyway.
        setIntroCommand = newCommand(StandardTestDispatcher(testScheduler))
        arrangeCurrent(playingTrack(title = "Great Song"))
        mockClipOptions(null, null)

        setIntroCommand.handleMusicCommand(
            DefaultCommandContext(event), MusicCommandTest.playerManager, requestingUserDto, 0
        )
        advanceUntilIdle()

        val saved = slot<MusicDto>()
        verify { musicFileService.createNewMusicFile(capture(saved)) }
        assertEquals("Great Song", saved.captured.fileName)
        coVerify(exactly = 0) { httpHelper.getYouTubeVideoTitle(any()) }
    }

    @Test
    fun `current checks the length against the track it already has loaded`() = runTest {
        setIntroCommand = newCommand(StandardTestDispatcher(testScheduler))
        arrangeCurrent(playingTrack())
        mockClipOptions(null, null)

        setIntroCommand.handleMusicCommand(
            DefaultCommandContext(event), MusicCommandTest.playerManager, requestingUserDto, 0
        )
        advanceUntilIdle()

        verify { musicFileService.createNewMusicFile(any()) }
        coVerify(exactly = 0) { httpHelper.getYouTubeVideoDuration(any()) }
    }

    @Test
    fun `explicit clip bounds override the captured window`() = runTest {
        setIntroCommand = newCommand(StandardTestDispatcher(testScheduler))
        arrangeCurrent(playingTrack())
        mockClipOptions("0:30", "0:40")

        setIntroCommand.handleMusicCommand(
            DefaultCommandContext(event), MusicCommandTest.playerManager, requestingUserDto, 0
        )
        advanceUntilIdle()

        val saved = slot<MusicDto>()
        verify { musicFileService.createNewMusicFile(capture(saved)) }
        assertEquals(30_000, saved.captured.startMs)
        assertEquals(40_000, saved.captured.endMs)
    }

    @Test
    fun `an overridden clip still has to obey the length cap`() = runTest {
        setIntroCommand = newCommand(StandardTestDispatcher(testScheduler))
        arrangeCurrent(playingTrack())
        mockClipOptions("0:30", "1:30")

        setIntroCommand.handleMusicCommand(
            DefaultCommandContext(event), MusicCommandTest.playerManager, requestingUserDto, 0
        )
        advanceUntilIdle()

        verify(exactly = 0) { musicFileService.createNewMusicFile(any()) }
        verify { event.hook.sendMessage(match<String> { it.contains("Clip is too long") }) }
    }

    @Test
    fun `current says so when nothing is playing`() = runTest {
        setIntroCommand = newCommand(StandardTestDispatcher(testScheduler))
        arrangeCurrent(null)
        mockClipOptions(null, null)

        setIntroCommand.handleMusicCommand(
            DefaultCommandContext(event), MusicCommandTest.playerManager, requestingUserDto, 0
        )
        advanceUntilIdle()

        verify(exactly = 0) { musicFileService.createNewMusicFile(any()) }
        verify { event.hook.sendMessage(match<String> { it.contains("Nothing is playing") }) }
    }

    @Test
    fun `current refuses to capture an intro that is mid-playback`() = runTest {
        // Someone joined a second ago; capturing their intro as your own is
        // almost certainly not what the command was aimed at.
        setIntroCommand = newCommand(StandardTestDispatcher(testScheduler))
        arrangeCurrent(playingTrack(), isIntro = true)
        mockClipOptions(null, null)

        setIntroCommand.handleMusicCommand(
            DefaultCommandContext(event), MusicCommandTest.playerManager, requestingUserDto, 0
        )
        advanceUntilIdle()

        verify(exactly = 0) { musicFileService.createNewMusicFile(any()) }
        verify { event.hook.sendMessage(match<String> { it.contains("That's an intro playing") }) }
    }

    @Test
    fun `current refuses a live stream, which has no fixed clip to save`() = runTest {
        setIntroCommand = newCommand(StandardTestDispatcher(testScheduler))
        arrangeCurrent(playingTrack(durationMs = Long.MAX_VALUE, isStream = true))
        mockClipOptions(null, null)

        setIntroCommand.handleMusicCommand(
            DefaultCommandContext(event), MusicCommandTest.playerManager, requestingUserDto, 0
        )
        advanceUntilIdle()

        verify(exactly = 0) { musicFileService.createNewMusicFile(any()) }
        verify { event.hook.sendMessage(match<String> { it.contains("live stream") }) }
    }

    @Test
    fun `current refuses a track with no re-playable link`() = runTest {
        setIntroCommand = newCommand(StandardTestDispatcher(testScheduler))
        arrangeCurrent(playingTrack(uri = null))
        mockClipOptions(null, null)

        setIntroCommand.handleMusicCommand(
            DefaultCommandContext(event), MusicCommandTest.playerManager, requestingUserDto, 0
        )
        advanceUntilIdle()

        verify(exactly = 0) { musicFileService.createNewMusicFile(any()) }
        verify { event.hook.sendMessage(match<String> { it.contains("no link to save") }) }
    }

    // --- `copy`: take someone else's -----------------------------------------

    private fun theirIntros(vararg specs: Pair<Int, String>): UserDto =
        UserDto(discordId = 99L, guildId = 1L).apply {
            musicDtos = specs.map { (slot, name) ->
                MusicDto(this, slot, name, 70, "https://youtu.be/$name".toByteArray(), 2_000, 9_000)
            }.toMutableList()
        }

    private fun arrangeCopy(source: UserDto, slot: Int? = null, into: Int? = null) {
        mockSub("copy")
        val sourceMember = mockk<Member>(relaxed = true) {
            every { idLong } returns 99L
            every { effectiveName } returns "Them"
        }
        every { event.getOption("from") } returns mockk { every { asMember } returns sourceMember }
        every { event.getOption("slot") } returns slot?.let { v -> mockk { every { asInt } returns v } }
        every { event.getOption("into") } returns into?.let { v -> mockk { every { asInt } returns v } }
        every { userDtoHelper.calculateUserDto(99L, 1L) } returns source
    }

    @Test
    fun `copy puts someone else's only intro into your first free slot`() = runTest {
        setIntroCommand = newCommand(StandardTestDispatcher(testScheduler))
        arrangeCopy(theirIntros(1 to "banger"))
        every { requestingUserDto.musicDtos } returns mutableListOf(
            MusicDto(UserDto(1, 1), 1, "mine", 20, byteArrayOf(1))
        )

        setIntroCommand.handleMusicCommand(
            DefaultCommandContext(event), MusicCommandTest.playerManager, requestingUserDto, 0
        )
        advanceUntilIdle()

        val saved = slot<MusicDto>()
        verify { musicFileService.createNewMusicFile(capture(saved)) }
        assertEquals(2, saved.captured.index)
        assertEquals("banger", saved.captured.fileName)
        assertEquals(70, saved.captured.introVolume)
        assertEquals(2_000, saved.captured.startMs)
        assertEquals(9_000, saved.captured.endMs)
    }

    @Test
    fun `a copy starts with a clean history rather than inheriting theirs`() = runTest {
        // Play counts and failures belong to their row. Inheriting a BROKEN
        // marker onto a brand-new intro would be a mystery with no fix.
        setIntroCommand = newCommand(StandardTestDispatcher(testScheduler))
        val theirs = theirIntros(1 to "banger").apply {
            musicDtos.single().apply {
                playCount = 42
                failureCount = 5
                lastFailureReason = "region blocked"
                measuredRms = 0.12
                enabled = false
            }
        }
        arrangeCopy(theirs)
        every { requestingUserDto.musicDtos } returns mutableListOf()

        setIntroCommand.handleMusicCommand(
            DefaultCommandContext(event), MusicCommandTest.playerManager, requestingUserDto, 0
        )
        advanceUntilIdle()

        val saved = slot<MusicDto>()
        verify { musicFileService.createNewMusicFile(capture(saved)) }
        assertEquals(0, saved.captured.playCount)
        assertEquals(0, saved.captured.failureCount)
        assertEquals(null, saved.captured.lastFailureReason)
        assertTrue(saved.captured.enabled, "a copy you just made should play")
        // The loudness measurement is a property of the audio, so it travels.
        assertEquals(0.12, saved.captured.measuredRms)
    }

    @Test
    fun `copy takes the slot you name from a member with several`() = runTest {
        setIntroCommand = newCommand(StandardTestDispatcher(testScheduler))
        arrangeCopy(theirIntros(1 to "first", 2 to "second", 3 to "third"), slot = 2)
        every { requestingUserDto.musicDtos } returns mutableListOf()

        setIntroCommand.handleMusicCommand(
            DefaultCommandContext(event), MusicCommandTest.playerManager, requestingUserDto, 0
        )
        advanceUntilIdle()

        val saved = slot<MusicDto>()
        verify { musicFileService.createNewMusicFile(capture(saved)) }
        assertEquals("second", saved.captured.fileName)
    }

    @Test
    fun `copy asks which one when the member has several and you did not say`() = runTest {
        setIntroCommand = newCommand(StandardTestDispatcher(testScheduler))
        arrangeCopy(theirIntros(1 to "first", 3 to "third"))
        every { requestingUserDto.musicDtos } returns mutableListOf()

        setIntroCommand.handleMusicCommand(
            DefaultCommandContext(event), MusicCommandTest.playerManager, requestingUserDto, 0
        )
        advanceUntilIdle()

        verify(exactly = 0) { musicFileService.createNewMusicFile(any()) }
        verify { event.hook.sendMessage(match<String> { it.contains("#1, #3") && it.contains("slot:") }) }
    }

    @Test
    fun `copy into an occupied slot replaces what is there`() = runTest {
        setIntroCommand = newCommand(StandardTestDispatcher(testScheduler))
        arrangeCopy(theirIntros(1 to "banger"), into = 2)
        val mine = MusicDto(UserDto(1, 1), 2, "old", 20, byteArrayOf(1))
        every { requestingUserDto.musicDtos } returns mutableListOf(mine)

        setIntroCommand.handleMusicCommand(
            DefaultCommandContext(event), MusicCommandTest.playerManager, requestingUserDto, 0
        )
        advanceUntilIdle()

        verify(exactly = 0) { musicFileService.createNewMusicFile(any()) }
        val saved = slot<MusicDto>()
        verify { musicFileService.updateMusicFile(capture(saved)) }
        assertEquals("1_1_2", saved.captured.id)
        assertEquals("banger", saved.captured.fileName)
    }

    @Test
    fun `copy at the slot limit explains how to make room`() = runTest {
        setIntroCommand = newCommand(StandardTestDispatcher(testScheduler))
        arrangeCopy(theirIntros(1 to "banger"))
        every { requestingUserDto.musicDtos } returns (1..3).map {
            MusicDto(UserDto(1, 1), it, "mine$it", 20, byteArrayOf(1))
        }.toMutableList()

        setIntroCommand.handleMusicCommand(
            DefaultCommandContext(event), MusicCommandTest.playerManager, requestingUserDto, 0
        )
        advanceUntilIdle()

        verify(exactly = 0) { musicFileService.createNewMusicFile(any()) }
        verify { event.hook.sendMessage(match<String> { it.contains("into:") && it.contains("/deleteintro") }) }
    }

    @Test
    fun `copying from someone with no intro says so`() = runTest {
        setIntroCommand = newCommand(StandardTestDispatcher(testScheduler))
        arrangeCopy(theirIntros())
        every { requestingUserDto.musicDtos } returns mutableListOf()

        setIntroCommand.handleMusicCommand(
            DefaultCommandContext(event), MusicCommandTest.playerManager, requestingUserDto, 0
        )
        advanceUntilIdle()

        verify(exactly = 0) { musicFileService.createNewMusicFile(any()) }
        verify { event.hook.sendMessage(match<String> { it.contains("Them hasn't set an intro") }) }
    }

    @Test
    fun `copying a slot they do not have lists the ones they do`() = runTest {
        setIntroCommand = newCommand(StandardTestDispatcher(testScheduler))
        arrangeCopy(theirIntros(1 to "first"), slot = 3)
        every { requestingUserDto.musicDtos } returns mutableListOf()

        setIntroCommand.handleMusicCommand(
            DefaultCommandContext(event), MusicCommandTest.playerManager, requestingUserDto, 0
        )
        advanceUntilIdle()

        verify(exactly = 0) { musicFileService.createNewMusicFile(any()) }
        verify { event.hook.sendMessage(match<String> { it.contains("nothing in slot #3") && it.contains("#1") }) }
    }

    @Test
    fun `a refused copy is reported rather than passing silently`() = runTest {
        setIntroCommand = newCommand(StandardTestDispatcher(testScheduler))
        arrangeCopy(theirIntros(1 to "banger"))
        every { requestingUserDto.musicDtos } returns mutableListOf()
        every { musicFileService.createNewMusicFile(any()) } returns null

        setIntroCommand.handleMusicCommand(
            DefaultCommandContext(event), MusicCommandTest.playerManager, requestingUserDto, 0
        )
        advanceUntilIdle()

        verify { event.hook.sendMessage(match<String> { it.contains("Couldn't copy") }) }
    }

    private fun setupMentions(userOptionMapping: OptionMapping) {
        val mentions = mockk<Mentions>()
        val mentionedMember = mockk<Member>(relaxed = true)
        every { userOptionMapping.mentions } returns mentions
        every { mentions.members } returns listOf(mentionedMember)
        every { mentionedMember.idLong } returns 1L
        every { mentionedMember.effectiveName } returns "Another Username"
    }

    private fun setupAttachments(
        attachmentOptionMapping: OptionMapping,
        fileExt: String = "mp3",
        fileSize: Int = 1000
    ) {
        every { attachmentOptionMapping.asAttachment } returns mockk {
            every { fileExtension } returns fileExt
            every { fileName } returns "filename"
            every { size } returns fileSize
            every { proxy } returns mockk {
                every { download() } returns CompletableFuture.completedFuture(mockk(relaxed = true))
            }
        }
    }
}
