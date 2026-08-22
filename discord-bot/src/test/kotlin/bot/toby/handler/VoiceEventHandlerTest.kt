import bot.toby.handler.VoiceEventHandler
import bot.toby.helpers.MusicPlayerHelper
import bot.toby.helpers.IntroHelper
import common.intro.IntroLoudness
import bot.toby.helpers.UserDtoHelper
import bot.toby.intro.IntroPlaybackTracker
import bot.toby.lavaplayer.PlayerManager
import bot.toby.managers.NowPlayingManager
import bot.toby.voice.LastConnectedChannelTracker
import bot.toby.voice.VoiceSessionLifecycle
import database.dto.guild.ConfigDto
import database.dto.music.MusicDto
import database.service.guild.ConfigService
import database.service.social.SocialCreditAwardService
import database.service.leveling.XpAwardService
import io.mockk.*
import io.mockk.junit5.MockKExtension
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.SelfUser
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.managers.AudioManager
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction
import net.dv8tion.jda.api.utils.cache.SnowflakeCacheView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MockKExtension::class)
class VoiceEventHandlerTest {

    private val jda: JDA = mockk()
    private val selfUser = mockk<SelfUser>()
    private val configService: ConfigService = mockk()
    private val userDtoHelper: UserDtoHelper = mockk()
    private val introHelper: IntroHelper = mockk()
    private val voiceSessionLifecycle: VoiceSessionLifecycle = mockk(relaxed = true)
    private val lastConnectedChannelTracker: LastConnectedChannelTracker = LastConnectedChannelTracker()
    private val awardService: SocialCreditAwardService = mockk(relaxed = true)
    private val xpAwardService: XpAwardService = mockk(relaxed = true)
    private val nowPlayingManager: NowPlayingManager = mockk(relaxed = true)

    // JUnit builds a fresh test instance per method, so this tracker (and the
    // handler holding it) starts empty for every test.
    private val introPlaybackTracker = IntroPlaybackTracker()

    private val handler = spyk(
        VoiceEventHandler(
            configService,
            userDtoHelper,
            introHelper,
            voiceSessionLifecycle,
            lastConnectedChannelTracker,
            awardService,
            xpAwardService,
            nowPlayingManager,
            introPlaybackTracker,
        )
    )

    @BeforeEach
    fun setup() {
        every { jda.selfUser } returns selfUser
        every { selfUser.name } returns "TestBot"
        every { selfUser.idLong } returns 12345L
        // The guild-level intro switches are opt-out, so "no row stored" is
        // the default every existing test wants. Stubbed for any guild here
        // rather than per-test; the tests that exercise the switches override.
        introConfigDefaults()
    }

    /** No stored value for any of the intro guild-config keys. */
    private fun introConfigDefaults() {
        listOf(
            ConfigDto.Configurations.INTROS_ENABLED,
            ConfigDto.Configurations.INTRO_EXCLUDED_CHANNELS,
            ConfigDto.Configurations.INTRO_NORMALISE_VOLUME,
        ).forEach { key ->
            every { configService.getConfigByName(key.configValue, any()) } returns null
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `onReady should connect to the most populated voice channel`() {
        val guild1 = mockk<Guild>()
        val guild2 = mockk<Guild>()
        val readyEvent = mockk<ReadyEvent>()
        val voiceChannel1 = mockk<VoiceChannel>()
        val voiceChannel2 = mockk<VoiceChannel>()
        val nonBotMember1 = mockk<Member>()
        val nonBotMember2 = mockk<Member>()
        val botMember = mockk<Member>()
        val audioManager1 = mockk<AudioManager>()
        val audioManager2 = mockk<AudioManager>()
        val guildCache = mockk<SnowflakeCacheView<Guild>>()
        val commandListUpdateAction = mockk<CommandListUpdateAction>()

        // Mocking the chain
        every { readyEvent.jda } returns jda
        every { readyEvent.jda.selfUser } returns selfUser
        every { readyEvent.jda.updateCommands() } returns commandListUpdateAction
        every { readyEvent.jda.guildCache } returns guildCache
        every { commandListUpdateAction.addCommands(any<List<CommandData>>()) } returns commandListUpdateAction
        every { commandListUpdateAction.queue() } just Runs

        every { guildCache.iterator() } returns mutableListOf(guild1, guild2).iterator()

        every { guild1.voiceChannels } returns listOf(voiceChannel1)
        every { guild1.idLong } returns 1L
        every { guild1.id } returns "1"
        every { guild1.name } returns "Guild 1"
        every { guild2.voiceChannels } returns listOf(voiceChannel2)
        every { guild2.idLong } returns 2L
        every { guild2.id } returns "2"
        every { guild2.name } returns "Guild 2"

        every { voiceChannel1.members } returns listOf(nonBotMember1, botMember)
        every { voiceChannel1.name } returns "voiceChannel1Name"
        every { voiceChannel1.guild } returns guild1
        every { voiceChannel2.members } returns listOf(nonBotMember2)
        every { voiceChannel2.name } returns "voiceChannel2Name"
        every { voiceChannel2.guild } returns guild2

        every { nonBotMember1.user.isBot } returns false
        every { nonBotMember2.user.isBot } returns false
        every { botMember.user.isBot } returns true
        // Needed for the startup voice-session reconciliation that runs
        // before connectToMostPopulatedVoiceChannel.
        every { nonBotMember1.idLong } returns 11L
        every { nonBotMember2.idLong } returns 22L
        every { voiceChannel1.idLong } returns 101L
        every { voiceChannel2.idLong } returns 102L

        every { guild1.audioManager } returns audioManager1
        every { guild2.audioManager } returns audioManager2

        every { audioManager1.isConnected } returns false
        every { audioManager2.isConnected } returns false
        every { audioManager1.openAudioConnection(any()) } just Runs
        every { audioManager2.openAudioConnection(any()) } just Runs

        val handler = VoiceEventHandler(
            configService = mockk(),
            userDtoHelper = mockk(),
            introHelper = mockk(),
            voiceSessionLifecycle = mockk(relaxed = true),
            lastConnectedChannelTracker = LastConnectedChannelTracker(),
            awardService = mockk(relaxed = true),
            xpAwardService = mockk(relaxed = true),
            nowPlayingManager = mockk(relaxed = true),
        )

        handler.onReady(readyEvent)

        verify(exactly = 1) { audioManager1.openAudioConnection(voiceChannel1) }
        verify(exactly = 1) { audioManager2.openAudioConnection(voiceChannel2) }
    }

    @Test
    fun `onReady opens a fresh voice session for every currently-connected non-bot member`() {
        // Reproduces the "deploy in the middle of voice eats the post-deploy
        // span" bug: without this reconciliation, the user's eventual leave
        // event has no open session to close, and the time between bot-ready
        // and leave is silently discarded.
        val guild = mockk<Guild>(relaxed = true)
        val readyEvent = mockk<ReadyEvent>(relaxed = true)
        val voiceChannel = mockk<VoiceChannel>(relaxed = true)
        val audioManager = mockk<AudioManager>(relaxed = true)
        val human1 = mockk<Member>(relaxed = true)
        val human2 = mockk<Member>(relaxed = true)
        val bot = mockk<Member>(relaxed = true)
        val voiceSessionLifecycle = mockk<VoiceSessionLifecycle>(relaxed = true)

        every { readyEvent.jda } returns jda
        every { jda.guildCache.iterator() } returns mutableListOf(guild).iterator()
        every { guild.idLong } returns 7L
        every { guild.voiceChannels } returns listOf(voiceChannel)
        every { guild.audioManager } returns audioManager
        every { voiceChannel.idLong } returns 99L
        every { voiceChannel.guild } returns guild
        every { voiceChannel.members } returns listOf(human1, human2, bot)
        every { human1.user.isBot } returns false
        every { human2.user.isBot } returns false
        every { bot.user.isBot } returns true
        every { human1.idLong } returns 100L
        every { human2.idLong } returns 200L
        every { bot.idLong } returns 999L  // explicit so the verify-block matcher doesn't touch the relaxed mock

        val handler = VoiceEventHandler(
            configService = mockk(relaxed = true),
            userDtoHelper = mockk(relaxed = true),
            introHelper = mockk(relaxed = true),
            voiceSessionLifecycle = voiceSessionLifecycle,
            lastConnectedChannelTracker = LastConnectedChannelTracker(),
            awardService = mockk(relaxed = true),
            xpAwardService = mockk(relaxed = true),
            nowPlayingManager = mockk(relaxed = true),
        )

        handler.onReady(readyEvent)

        // Both humans get a session; the bot does NOT.
        verify(exactly = 1) { voiceSessionLifecycle.openSession(100L, 7L, voiceChannel, any()) }
        verify(exactly = 1) { voiceSessionLifecycle.openSession(200L, 7L, voiceChannel, any()) }
        verify(exactly = 0) { voiceSessionLifecycle.openSession(999L, any(), any(), any()) }
    }


    @Test
    fun `onGuildVoiceUpdate should handle voice join`() {
        val guild = mockk<Guild>()
        val event = mockk<GuildVoiceUpdateEvent>()
        val audioManager = mockk<AudioManager>()
        val member = mockk<Member>()
        val channel = mockk<AudioChannelUnion>()
        val nonBotMember = mockk<Member>()
        val audioPlayerManager = mockk<PlayerManager>()

        every { event.guild } returns guild
        every { event.jda.selfUser } returns selfUser
        every { guild.audioManager } returns audioManager
        every { event.member } returns member
        every { event.channelJoined } returns channel
        every { event.channelLeft } returns null
        every { channel.members } returns listOf(nonBotMember)
        every { channel.name } returns "voiceChannelName"
        every { channel.idLong } returns 99L
        every { channel.asVoiceChannel() } returns mockk(relaxed = true)
        every { nonBotMember.user.isBot } returns false
        every { member.guild } returns guild
        every { member.isOwner } returns false
        every { member.idLong } returns 1L
        every { member.id } returns "1234"
        every { member.effectiveName } returns "Effective Name"
        every { member.user } returns mockk {
            every { idLong } returns 1L
        }
        every { guild.idLong } returns 1L
        every { guild.id } returns "1"
        every { guild.id } returns "1"
        every { guild.name } returns "guildName"
        every { audioManager.isConnected } returns false
        every { audioManager.connectedChannel } returns null
        every { audioManager.openAudioConnection(channel) } just Runs

        mockkObject(PlayerManager)
        every { PlayerManager.instance } returns audioPlayerManager
        every { audioPlayerManager.getMusicManager(guild).audioPlayer.volume = any() } just Runs
        // This test is about connecting and setting the volume. It used to
        // stop there by accident: connectedChannel is stubbed null, which the
        // old guard read as "not in the joined channel", so the intro was
        // skipped. It no longer is, so keep the helper from descending into a
        // real player.
        mockkObject(MusicPlayerHelper)
        every {
            MusicPlayerHelper.playUserIntro(any(), any(), any(), any(), any(), any(), any(), any())
        } returns null

        val deleteDelayConfig = ConfigDto()
        deleteDelayConfig.value = "30"
        every { configService.getConfigByName(ConfigDto.Configurations.VOLUME.configValue, "1") } returns null
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.DELETE_DELAY.configValue, "1"
            )
        } returns deleteDelayConfig

        every { userDtoHelper.calculateUserDto(1, 1, false) } returns mockk(relaxed = true) {
            every { musicDtos } returns listOf(mockk<MusicDto>()).toMutableList()
        }

        handler.onGuildVoiceUpdate(event)

        verify {
            audioManager.openAudioConnection(channel)
            PlayerManager.instance
            audioPlayerManager.getMusicManager(guild).audioPlayer.volume = any()
        }

        unmockkObject(MusicPlayerHelper)
    }

    @Test
    fun `onGuildVoiceUpdate should prompt user to set intro when they have none`() {
        val guild = mockk<Guild>()
        val event = mockk<GuildVoiceUpdateEvent>()
        val audioManager = mockk<AudioManager>()
        val member = mockk<Member>()
        val channel = mockk<AudioChannelUnion>()
        val nonBotMember = mockk<Member>()
        val audioPlayerManager = mockk<PlayerManager>()

        every { event.guild } returns guild
        every { event.jda.selfUser } returns selfUser
        every { guild.audioManager } returns audioManager
        every { event.member } returns member
        every { event.channelJoined } returns channel
        every { event.channelLeft } returns null
        every { channel.members } returns listOf(nonBotMember)
        every { channel.name } returns "voiceChannelName"
        every { channel.idLong } returns 99L
        every { channel.asVoiceChannel() } returns mockk(relaxed = true)
        every { nonBotMember.user.isBot } returns false
        every { member.guild } returns guild
        every { member.isOwner } returns false
        every { member.idLong } returns 1L
        every { member.id } returns "1234"
        every { member.effectiveName } returns "Effective Name"
        every { member.user } returns mockk {
            every { idLong } returns 1L
        }
        every { guild.idLong } returns 1L
        every { guild.id } returns "1"
        every { guild.id } returns "1"
        every { guild.name } returns "guildName"
        every { audioManager.isConnected } returns false
        every { audioManager.connectedChannel } returns null
        every { audioManager.openAudioConnection(channel) } just Runs

        mockkObject(PlayerManager)
        every { PlayerManager.instance } returns audioPlayerManager
        every { audioPlayerManager.getMusicManager(guild).audioPlayer.volume = any() } just Runs

        val deleteDelayConfig = ConfigDto()
        deleteDelayConfig.value = "30"
        every { configService.getConfigByName(ConfigDto.Configurations.VOLUME.configValue, "1") } returns null
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.DELETE_DELAY.configValue, "1"
            )
        } returns deleteDelayConfig

        every { userDtoHelper.calculateUserDto(1, 1, false) } returns mockk(relaxed = true) {
            every { musicDtos } returns emptyList<MusicDto>().toMutableList()
        }

        every { introHelper.promptUserForMusicInfo(any(), any()) } just Runs

        handler.onGuildVoiceUpdate(event)

        verify {
            audioManager.openAudioConnection(channel)
            PlayerManager.instance
            audioPlayerManager.getMusicManager(guild).audioPlayer.volume = any()
            introHelper.promptUserForMusicInfo(any<User>(), any<Guild>())
        }
    }

    @Test
    fun `onGuildVoiceUpdate should handle voice leave`() {
        val guild = mockk<Guild>()
        val event = mockk<GuildVoiceUpdateEvent>()
        val audioManager = mockk<AudioManager>()
        val channel = mockk<AudioChannelUnion>()

        every { event.guild } returns guild
        every { event.jda.selfUser } returns selfUser
        every { guild.audioManager } returns audioManager
        every { event.channelJoined } returns null
        every { event.channelLeft } returns channel
        every { event.member } returns mockk {
            every { effectiveName } returns "Effective Name"
            every { idLong } returns 123L
            every { id } returns "1234"
            every { user } returns mockk {
                every { idLong } returns 123L
            }
        }
        every { channel.members } returns emptyList()
        every { guild.idLong } returns 1L
        every { guild.id } returns "1"
        every { guild.name } returns "guildName"
        every { channel.name } returns "team 1"
        every { audioManager.connectedChannel } returns null
        every { channel.delete().queue() } just Runs

        handler.onGuildVoiceUpdate(event)

        verify {
            channel.delete().queue()
        }
    }

    @Test
    fun `onGuildVoiceMove should rejoin previous channel when bot is moved`() {
        val guild = mockk<Guild>()
        val event = mockk<GuildVoiceUpdateEvent>()
        val audioManager = mockk<AudioManager>()
        val member = mockk<Member>()
        val previousChannel = mockk<VoiceChannel>()
        val newChannel = mockk<AudioChannelUnion>(relaxed = true)

        // Mocking event and guild behavior
        every { event.guild } returns guild
        every { event.member } returns member
        every { guild.audioManager } returns audioManager
        every { audioManager.guild } returns guild
        every { guild.idLong } returns 1L
        every { guild.id } returns "1"
        every { guild.name } returns "guildName"
        every { member.user.idLong } returns 12345L  // Simulate the bot's ID
        every { member.effectiveName } returns "effectiveName" // Simulate the bot's ID
        every { member.idLong } returns 1234L
        every { member.id } returns "1234"
        every { event.jda.selfUser.idLong } returns 12345L  // Simulate the bot's self ID
        every { event.jda.selfUser.name } returns "TobyBot"  // Simulate the bot's self ID
        every { event.channelJoined } returns newChannel
        every { event.channelLeft } returns mockk()

        // Simulate previous channel in the tracker. The handler resolves the
        // VoiceChannel via Guild.getVoiceChannelById at use time, so wire that.
        every { previousChannel.idLong } returns 7777L
        every { guild.getVoiceChannelById(7777L) } returns previousChannel
        lastConnectedChannelTracker.set(guild.idLong, 7777L)

        // Mock the audioManager behavior
        every { audioManager.openAudioConnection(any()) } just Runs
        every { previousChannel.name } returns "PreviousChannel"

        handler.onGuildVoiceUpdate(event)

        // Verifying that the bot tries to rejoin the previous channel
        verify(exactly = 1) { audioManager.openAudioConnection(previousChannel) }
    }

    @Test
    fun `onGuildVoiceMove should log warning when bot is moved and no previous channel exists`() {
        val guild = mockk<Guild>()
        val event = mockk<GuildVoiceUpdateEvent>()
        val audioManager = mockk<AudioManager>()
        val member = mockk<Member>()

        // Mocking event and guild behavior
        every { event.guild } returns guild
        every { event.member } returns member
        every { guild.audioManager } returns audioManager
        every { guild.name } returns "guildName"
        every { audioManager.guild } returns guild
        every { member.user.idLong } returns 12345L  // Simulate the bot's ID
        every { member.idLong } returns 12345L  // Simulate the bot's ID
        every { member.id } returns "12345"  // Simulate the bot's ID
        every { event.jda.selfUser.idLong } returns 12345L  // Simulate the bot's self ID
        every { event.jda.selfUser.name } returns "TobyBot"  // Simulate the bot's self ID
        every { guild.idLong } returns 1L
        every { guild.id } returns "1"
        every { event.channelJoined } returns mockk {
            every { asVoiceChannel() } returns mockk(relaxed = true)
        }
        every { event.channelLeft } returns mockk {
            every { asVoiceChannel() } returns mockk(relaxed = true)
        }

        every { member.effectiveName } returns "BotName"


        // Simulate no previous channel in the tracker
        lastConnectedChannelTracker.clear(guild.idLong)

        handler.onGuildVoiceUpdate(event)

        // Verifying that a warning log is printed
        verify(exactly = 0) { audioManager.closeAudioConnection() }
        verify(exactly = 0) { audioManager.openAudioConnection(any()) }
    }


    @Test
    fun `onGuildVoiceMove should check to close connection and join user`() {
        val guild = mockk<Guild>()
        val event = mockk<GuildVoiceUpdateEvent>()
        val audioManager = mockk<AudioManager>(relaxed = true)
        val member = mockk<Member>()
        val newChannel = mockk<AudioChannelUnion>(relaxed = true)

        every { event.guild } returns guild
        every { event.jda.selfUser } returns selfUser
        every { event.member } returns member
        every { event.channelJoined } returns newChannel
        every { event.channelLeft } returns mockk()
        every { guild.audioManager } returns audioManager
        every { audioManager.guild } returns guild
        every { member.user.idLong } returns 54321L  // Not bot's ID
        every { member.idLong } returns 54321L  // Not bot's ID
        every { member.id } returns "54321"  // Not bot's ID
        every { guild.idLong } returns 1L
        every { guild.id } returns "1"
        every { guild.name } returns "guildName"
        every { member.effectiveName } returns "Some User"
        every { member.user.isBot } returns false
        every { newChannel.members } returns listOf(member)

        // The relaxed audioManager reports an empty connected channel, so closing
        // the connection now also stops playback — stub the player manager so that
        // teardown doesn't reach into the real singleton.
        val playerManager = mockk<PlayerManager>(relaxed = true)
        mockkObject(PlayerManager)
        every { PlayerManager.instance } returns playerManager

        // Mock configuration service
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.VOLUME.configValue,
                "1"
            )
        } returns ConfigDto().apply { value = "50" }

        handler.onGuildVoiceUpdate(event)

        // Verify that the bot stopped playback, cleared the now-playing message,
        // closed the connection, and joined the new channel.
        verify(exactly = 1) { nowPlayingManager.resetNowPlayingMessage(1L) }
        verify(exactly = 1) { audioManager.closeAudioConnection() }
        verify(exactly = 1) { audioManager.openAudioConnection(newChannel) }

        unmockkObject(PlayerManager)
    }

    @Test
    fun `voice leave that empties the channel stops playback and clears now-playing`() {
        val guild = mockk<Guild>(relaxed = true)
        val event = mockk<GuildVoiceUpdateEvent>(relaxed = true)
        val audioManager = mockk<AudioManager>(relaxed = true)
        val member = mockk<Member>(relaxed = true)
        val emptyChannel = mockk<AudioChannelUnion>(relaxed = true)

        every { event.guild } returns guild
        every { event.jda.selfUser } returns selfUser
        every { event.member } returns member
        every { event.channelJoined } returns null
        every { event.channelLeft } returns emptyChannel
        every { guild.audioManager } returns audioManager
        every { audioManager.guild } returns guild
        every { guild.idLong } returns 99L
        every { member.user.idLong } returns 54321L  // Not bot's ID
        every { member.idLong } returns 54321L
        // Bot is still connected but the channel has no humans left.
        every { audioManager.connectedChannel } returns emptyChannel
        every { emptyChannel.members } returns emptyList()

        val playerManager = mockk<PlayerManager>(relaxed = true)
        mockkObject(PlayerManager)
        every { PlayerManager.instance } returns playerManager

        handler.onGuildVoiceUpdate(event)

        verify(exactly = 1) { playerManager.getMusicManager(guild) }
        verify(exactly = 1) { nowPlayingManager.resetNowPlayingMessage(99L) }
        verify(exactly = 1) { audioManager.closeAudioConnection() }

        unmockkObject(PlayerManager)
    }

    @Test
    fun `onGuildLeave cleans up PlayerManager, NowPlayingManager, and last-connected channel`() {
        val guild = mockk<Guild>()
        val event = mockk<GuildLeaveEvent>()
        val playerManager = mockk<PlayerManager>(relaxed = true)

        every { event.guild } returns guild
        every { guild.idLong } returns 42L
        every { guild.name } returns "LeavingGuild"

        mockkObject(PlayerManager)
        every { PlayerManager.instance } returns playerManager

        // Pre-seed the tracker — leaving the guild should evict it.
        lastConnectedChannelTracker.set(42L, 1234L)

        handler.onGuildLeave(event)

        verify(exactly = 1) { playerManager.destroyMusicManager(42L) }
        verify(exactly = 1) { nowPlayingManager.resetNowPlayingMessage(42L) }
        assert(lastConnectedChannelTracker.resolveChannel(guild) == null) {
            "tracker entry for the left guild should be evicted"
        }

        unmockkObject(PlayerManager)
    }

    @Test
    fun `intro play awards social credit via award service`() {
        val guild = mockk<Guild>(relaxed = true)
        val event = mockk<GuildVoiceUpdateEvent>(relaxed = true)
        val audioManager = mockk<AudioManager>(relaxed = true)
        val member = mockk<Member>(relaxed = true)
        val channel = mockk<AudioChannelUnion>(relaxed = true)
        val audioPlayerManager = mockk<PlayerManager>(relaxed = true)

        every { event.guild } returns guild
        every { event.jda.selfUser } returns selfUser
        every { guild.audioManager } returns audioManager
        every { event.member } returns member
        every { event.channelJoined } returns channel
        every { event.channelLeft } returns null
        every { channel.members } returns listOf(member)
        every { member.user.isBot } returns false
        every { member.guild } returns guild
        every { member.isOwner } returns false
        every { member.idLong } returns 1L
        every { member.user.idLong } returns 1L
        every { guild.idLong } returns 7L
        every { guild.id } returns "7"
        every { audioManager.isConnected } returns false
        // Must match event.channelJoined so setupAndPlayUserIntro fires.
        every { audioManager.connectedChannel } returns channel

        mockkObject(PlayerManager)
        every { PlayerManager.instance } returns audioPlayerManager
        // The award is for an intro that PLAYED, so one has to. Left to the
        // real helper it returns null against relaxed mocks, and the credit is
        // no longer paid for silence.
        mockkObject(MusicPlayerHelper)
        every {
            MusicPlayerHelper.playUserIntro(any(), any(), any(), any(), any(), any(), any(), any())
        } returns mockk<MusicDto>(relaxed = true) { every { id } returns "7_1_1" }

        every { configService.getConfigByName(ConfigDto.Configurations.DELETE_DELAY.configValue, "7") } returns
            ConfigDto().apply { value = "30" }
        every { configService.getConfigByName(ConfigDto.Configurations.VOLUME.configValue, "7") } returns null
        every { userDtoHelper.calculateUserDto(1L, 7L, false) } returns mockk(relaxed = true) {
            every { discordId } returns 1L
            every { guildId } returns 7L
            every { musicDtos } returns listOf(mockk<MusicDto>(relaxed = true)).toMutableList()
        }

        handler.onGuildVoiceUpdate(event)

        verify(exactly = 1) {
            awardService.award(1L, 7L, VoiceEventHandler.INTRO_PLAY_CREDIT, "intro-play", any(), any())
        }

        unmockkObject(MusicPlayerHelper)
        unmockkObject(PlayerManager)
    }

    @Test
    fun `an intro that made no sound is not paid for`() {
        // Every intro switched off, a dead link, a stream that died — the
        // member hears nothing, and the credit landing anyway was both the
        // only feedback they got and the opposite of the truth. It also let
        // anyone switch all their intros off and farm the payout on each join.
        val guild = mockk<Guild>(relaxed = true)
        val event = mockk<GuildVoiceUpdateEvent>(relaxed = true)
        val audioManager = mockk<AudioManager>(relaxed = true)
        val member = mockk<Member>(relaxed = true)
        val channel = mockk<AudioChannelUnion>(relaxed = true)
        val audioPlayerManager = mockk<PlayerManager>(relaxed = true)

        every { event.guild } returns guild
        every { event.jda.selfUser } returns selfUser
        every { guild.audioManager } returns audioManager
        every { event.member } returns member
        every { event.channelJoined } returns channel
        every { event.channelLeft } returns null
        every { channel.members } returns listOf(member)
        every { member.user.isBot } returns false
        every { member.guild } returns guild
        every { member.isOwner } returns false
        every { member.idLong } returns 1L
        every { member.user.idLong } returns 1L
        every { guild.idLong } returns 7L
        every { guild.id } returns "7"
        every { audioManager.isConnected } returns false
        every { audioManager.connectedChannel } returns channel

        mockkObject(PlayerManager)
        every { PlayerManager.instance } returns audioPlayerManager
        mockkObject(MusicPlayerHelper)
        every {
            MusicPlayerHelper.playUserIntro(any(), any(), any(), any(), any(), any(), any(), any())
        } returns null

        every { configService.getConfigByName(ConfigDto.Configurations.DELETE_DELAY.configValue, "7") } returns
            ConfigDto().apply { value = "30" }
        every { configService.getConfigByName(ConfigDto.Configurations.VOLUME.configValue, "7") } returns null
        every { userDtoHelper.calculateUserDto(1L, 7L, false) } returns mockk(relaxed = true) {
            every { discordId } returns 1L
            every { guildId } returns 7L
            every { musicDtos } returns listOf(mockk<MusicDto>(relaxed = true)).toMutableList()
        }

        handler.onGuildVoiceUpdate(event)

        verify(exactly = 0) { awardService.award(any(), any(), any(), "intro-play", any(), any()) }
        verify(exactly = 0) { xpAwardService.award(any(), any(), any(), "intro-play", any(), any()) }

        unmockkObject(MusicPlayerHelper)
        unmockkObject(PlayerManager)
    }

    @Test
    fun `the first person into an empty channel still hears their intro`() {
        // openAudioConnection only sends the gateway voice-state update; the
        // audioConnection field that connectedChannel reads is assigned later,
        // when the handshake completes. Gating the intro on connectedChannel
        // therefore compared against null on every cold join, and the first
        // person in was skipped every single time — with no else branch and
        // not one log line to say so.
        val guild = mockk<Guild>(relaxed = true)
        val event = mockk<GuildVoiceUpdateEvent>(relaxed = true)
        val audioManager = mockk<AudioManager>(relaxed = true)
        val member = mockk<Member>(relaxed = true)
        val channel = mockk<AudioChannelUnion>(relaxed = true)
        val audioPlayerManager = mockk<PlayerManager>(relaxed = true)

        every { event.guild } returns guild
        every { event.jda.selfUser } returns selfUser
        every { guild.audioManager } returns audioManager
        every { event.member } returns member
        every { event.channelJoined } returns channel
        every { event.channelLeft } returns null
        every { channel.members } returns listOf(member)
        every { channel.idLong } returns 99L
        every { member.user.isBot } returns false
        every { member.guild } returns guild
        every { member.isOwner } returns false
        every { member.idLong } returns 1L
        every { member.user.idLong } returns 1L
        every { guild.idLong } returns 7L
        every { guild.id } returns "7"
        // Exactly the cold-join state: nothing connected yet, and the
        // connection this call opens has not completed.
        every { audioManager.isConnected } returns false
        every { audioManager.connectedChannel } returns null

        mockkObject(PlayerManager)
        every { PlayerManager.instance } returns audioPlayerManager
        mockkObject(MusicPlayerHelper)
        every {
            MusicPlayerHelper.playUserIntro(any(), any(), any(), any(), any(), any(), any(), any())
        } returns mockk<MusicDto>(relaxed = true) { every { id } returns "7_1_1" }

        every { configService.getConfigByName(ConfigDto.Configurations.DELETE_DELAY.configValue, "7") } returns
            ConfigDto().apply { value = "30" }
        every { configService.getConfigByName(ConfigDto.Configurations.VOLUME.configValue, "7") } returns null
        every { userDtoHelper.calculateUserDto(1L, 7L, false) } returns mockk(relaxed = true) {
            every { discordId } returns 1L
            every { guildId } returns 7L
            every { musicDtos } returns listOf(mockk<MusicDto>(relaxed = true)).toMutableList()
        }

        handler.onGuildVoiceUpdate(event)

        verify(exactly = 1) {
            MusicPlayerHelper.playUserIntro(any(), any(), any(), any(), any(), any(), any(), any())
        }

        unmockkObject(MusicPlayerHelper)
        unmockkObject(PlayerManager)
    }

    @Test
    fun `nobody's intro plays into a channel the bot is not in`() {
        // The other half of the same guard: the bot sits in one channel and
        // somebody joins another. It must not play there — and now says so in
        // the log rather than dropping the join on the floor.
        val guild = mockk<Guild>(relaxed = true)
        val event = mockk<GuildVoiceUpdateEvent>(relaxed = true)
        val audioManager = mockk<AudioManager>(relaxed = true)
        val member = mockk<Member>(relaxed = true)
        val joined = mockk<AudioChannelUnion>(relaxed = true)
        val elsewhere = mockk<AudioChannelUnion>(relaxed = true)
        val audioPlayerManager = mockk<PlayerManager>(relaxed = true)

        every { event.guild } returns guild
        every { event.jda.selfUser } returns selfUser
        every { guild.audioManager } returns audioManager
        every { event.member } returns member
        every { event.channelJoined } returns joined
        every { event.channelLeft } returns null
        every { joined.members } returns listOf(member)
        every { joined.idLong } returns 99L
        every { elsewhere.idLong } returns 55L
        every { member.user.isBot } returns false
        every { member.guild } returns guild
        every { member.isOwner } returns false
        every { member.idLong } returns 1L
        every { member.user.idLong } returns 1L
        every { guild.idLong } returns 7L
        every { guild.id } returns "7"
        // Already connected elsewhere, so nothing new is opened.
        every { audioManager.isConnected } returns true
        every { audioManager.connectedChannel } returns elsewhere

        mockkObject(PlayerManager)
        every { PlayerManager.instance } returns audioPlayerManager
        mockkObject(MusicPlayerHelper)

        every { configService.getConfigByName(ConfigDto.Configurations.DELETE_DELAY.configValue, "7") } returns
            ConfigDto().apply { value = "30" }
        every { configService.getConfigByName(ConfigDto.Configurations.VOLUME.configValue, "7") } returns null
        every { userDtoHelper.calculateUserDto(1L, 7L, false) } returns mockk(relaxed = true) {
            every { discordId } returns 1L
            every { guildId } returns 7L
            every { musicDtos } returns listOf(mockk<MusicDto>(relaxed = true)).toMutableList()
        }

        handler.onGuildVoiceUpdate(event)

        verify(exactly = 0) {
            MusicPlayerHelper.playUserIntro(any(), any(), any(), any(), any(), any(), any(), any())
        }

        unmockkObject(MusicPlayerHelper)
        unmockkObject(PlayerManager)
    }

    @Test
    fun `member join routes intro through loadAndPlayIntro (not loadAndPlay)`() {
        val guild = mockk<Guild>(relaxed = true)
        val event = mockk<GuildVoiceUpdateEvent>(relaxed = true)
        val audioManager = mockk<AudioManager>(relaxed = true)
        val member = mockk<Member>(relaxed = true)
        val channel = mockk<AudioChannelUnion>(relaxed = true)
        val audioPlayerManager = mockk<PlayerManager>(relaxed = true)

        every { event.guild } returns guild
        every { event.jda.selfUser } returns selfUser
        every { guild.audioManager } returns audioManager
        every { event.member } returns member
        every { event.channelJoined } returns channel
        every { event.channelLeft } returns null
        every { channel.members } returns listOf(member)
        every { member.user.isBot } returns false
        every { member.guild } returns guild
        every { member.isOwner } returns false
        every { member.idLong } returns 1L
        every { member.user.idLong } returns 1L
        every { guild.idLong } returns 9L
        every { guild.id } returns "9"
        every { audioManager.isConnected } returns false
        every { audioManager.connectedChannel } returns channel

        mockkObject(PlayerManager)
        every { PlayerManager.instance } returns audioPlayerManager

        every { configService.getConfigByName(ConfigDto.Configurations.DELETE_DELAY.configValue, "9") } returns
            ConfigDto().apply { value = "30" }
        every { configService.getConfigByName(ConfigDto.Configurations.VOLUME.configValue, "9") } returns null

        val musicDto = mockk<MusicDto>(relaxed = true) {
            every { id } returns "9_1_1"
            every { fileName } returns "https://example.com/intro.mp3"
            every { musicBlob } returns null
            every { introVolume } returns 75
            every { startMs } returns null
            every { endMs } returns null
            // Relaxed Boolean mocks default to false, which would take the
            // intro out of the rotation entirely.
            every { enabled } returns true
            every { measuredRms } returns null
        }
        every { userDtoHelper.calculateUserDto(1L, 9L, false) } returns mockk(relaxed = true) {
            every { discordId } returns 1L
            every { guildId } returns 9L
            every { musicDtos } returns mutableListOf(musicDto)
        }

        handler.onGuildVoiceUpdate(event)

        verify(atLeast = 1) {
            audioPlayerManager.loadAndPlayIntro(
                guild, null, "https://example.com/intro.mp3", 30, 0L, 75, null, "9_1_1",
            )
        }
        // Intros must never go through the regular loadAndPlay path now.
        verify(exactly = 0) {
            audioPlayerManager.loadAndPlay(any(), any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 0) {
            audioPlayerManager.loadAndPlay(any(), any(), any(), any(), any(), any(), any(), any())
        }

        unmockkObject(PlayerManager)
    }

    @Test
    fun `no intro award when user has no musicDto`() {
        val guild = mockk<Guild>(relaxed = true)
        val event = mockk<GuildVoiceUpdateEvent>(relaxed = true)
        val audioManager = mockk<AudioManager>(relaxed = true)
        val member = mockk<Member>(relaxed = true)
        val channel = mockk<AudioChannelUnion>(relaxed = true)
        val audioPlayerManager = mockk<PlayerManager>(relaxed = true)

        every { event.guild } returns guild
        every { event.jda.selfUser } returns selfUser
        every { guild.audioManager } returns audioManager
        every { event.member } returns member
        every { event.channelJoined } returns channel
        every { event.channelLeft } returns null
        every { channel.members } returns listOf(member)
        every { member.user.isBot } returns false
        every { member.guild } returns guild
        every { member.isOwner } returns false
        every { member.idLong } returns 1L
        every { member.user.idLong } returns 1L
        every { guild.idLong } returns 8L
        every { guild.id } returns "8"
        every { audioManager.isConnected } returns false
        every { audioManager.connectedChannel } returns channel

        mockkObject(PlayerManager)
        every { PlayerManager.instance } returns audioPlayerManager

        every { configService.getConfigByName(ConfigDto.Configurations.DELETE_DELAY.configValue, "8") } returns
            ConfigDto().apply { value = "30" }
        every { configService.getConfigByName(ConfigDto.Configurations.VOLUME.configValue, "8") } returns null
        every { introHelper.promptUserForMusicInfo(any(), any()) } just Runs
        every { userDtoHelper.calculateUserDto(1L, 8L, false) } returns mockk(relaxed = true) {
            every { discordId } returns 1L
            every { guildId } returns 8L
            every { musicDtos } returns mutableListOf()
        }

        handler.onGuildVoiceUpdate(event)

        verify(exactly = 0) {
            awardService.award(any(), any(), VoiceEventHandler.INTRO_PLAY_CREDIT, "intro-play", any(), any())
        }

        unmockkObject(PlayerManager)
    }

    /**
     * Builds a "member joins the channel the bot is already in" event for
     * guild [gid] with [intros] configured, runs the handler, and returns the
     * PlayerManager mock so callers can assert on playback.
     */
    private fun joinWithIntros(
        gid: Long,
        intros: MutableList<MusicDto>,
        introsEnabled: String? = null,
        excludedChannels: String? = null,
        normalise: String? = null,
        channelId: Long = 500L,
    ): PlayerManager {
        val guild = mockk<Guild>(relaxed = true)
        val event = mockk<GuildVoiceUpdateEvent>(relaxed = true)
        val audioManager = mockk<AudioManager>(relaxed = true)
        val member = mockk<Member>(relaxed = true)
        val channel = mockk<AudioChannelUnion>(relaxed = true)
        val audioPlayerManager = mockk<PlayerManager>(relaxed = true)

        every { event.guild } returns guild
        every { event.jda.selfUser } returns selfUser
        every { guild.audioManager } returns audioManager
        every { event.member } returns member
        every { event.channelJoined } returns channel
        every { event.channelLeft } returns null
        every { channel.members } returns listOf(member)
        every { member.user.isBot } returns false
        every { member.guild } returns guild
        every { member.isOwner } returns false
        every { member.idLong } returns 1L
        every { member.user.idLong } returns 1L
        every { guild.idLong } returns gid
        every { guild.id } returns gid.toString()
        every { audioManager.isConnected } returns false
        every { audioManager.connectedChannel } returns channel

        every { channel.idLong } returns channelId

        mockkObject(PlayerManager)
        every { PlayerManager.instance } returns audioPlayerManager
        every { configService.getConfigByName(ConfigDto.Configurations.DELETE_DELAY.configValue, gid.toString()) } returns
            ConfigDto().apply { value = "30" }
        every { configService.getConfigByName(ConfigDto.Configurations.VOLUME.configValue, gid.toString()) } returns null
        mapOf(
            ConfigDto.Configurations.INTROS_ENABLED to introsEnabled,
            ConfigDto.Configurations.INTRO_EXCLUDED_CHANNELS to excludedChannels,
            ConfigDto.Configurations.INTRO_NORMALISE_VOLUME to normalise,
        ).forEach { (key, value) ->
            every { configService.getConfigByName(key.configValue, gid.toString()) } returns
                value?.let { ConfigDto().apply { this.value = it } }
        }
        every { userDtoHelper.calculateUserDto(1L, gid, false) } returns mockk(relaxed = true) {
            every { discordId } returns 1L
            every { guildId } returns gid
            every { musicDtos } returns intros
        }

        handler.onGuildVoiceUpdate(event)
        return audioPlayerManager
    }

    @Test
    fun `rejoining inside the cooldown does not replay the intro or pay out again`() {
        val musicDto = mockk<MusicDto>(relaxed = true) {
            every { id } returns "11_1_1"
            every { fileName } returns "https://example.com/intro.mp3"
            every { musicBlob } returns null
            every { introVolume } returns 75
            every { startMs } returns null
            every { endMs } returns null
            // Relaxed Boolean mocks default to false, which would take the
            // intro out of the rotation entirely.
            every { enabled } returns true
        }

        // Channel hop: Discord delivers a join event each time.
        joinWithIntros(11L, mutableListOf(musicDto))
        val second = joinWithIntros(11L, mutableListOf(musicDto))

        // Exactly one playback and one payout across both joins.
        verify(exactly = 0) {
            second.loadAndPlayIntro(any(), any(), any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 1) {
            awardService.award(1L, 11L, VoiceEventHandler.INTRO_PLAY_CREDIT, "intro-play", any(), any())
        }
        verify(exactly = 1) {
            xpAwardService.award(1L, 11L, VoiceEventHandler.INTRO_PLAY_XP, "intro-play", any(), any(), any())
        }

        unmockkObject(PlayerManager)
    }

    @Test
    fun `the intro that played is remembered so the next pick can avoid it`() {
        val musicDto = mockk<MusicDto>(relaxed = true) {
            every { id } returns "12_1_1"
            every { fileName } returns "https://example.com/intro.mp3"
            every { musicBlob } returns null
            every { introVolume } returns 75
            every { startMs } returns null
            every { endMs } returns null
            // Relaxed Boolean mocks default to false, which would take the
            // intro out of the rotation entirely.
            every { enabled } returns true
        }

        joinWithIntros(12L, mutableListOf(musicDto))

        assertEquals("12_1_1", introPlaybackTracker.lastPlayedIntroId(12L, 1L))
        assertTrue(introPlaybackTracker.onCooldown(12L, 1L))

        unmockkObject(PlayerManager)
    }

    // --- guild-level intro controls ----------------------------------------

    /** An intro the handler will happily play, at volume 75. */
    private fun playableIntro(id: String, measured: Double? = null) = mockk<MusicDto>(relaxed = true) {
        every { this@mockk.id } returns id
        every { fileName } returns "https://example.com/intro.mp3"
        every { musicBlob } returns null
        every { introVolume } returns 75
        every { startMs } returns null
        every { endMs } returns null
        every { enabled } returns true
        every { failureCount } returns 0
        every { measuredRms } returns measured
    }

    @Test
    fun `a server that has switched intros off plays nothing on join`() {
        val manager = joinWithIntros(21L, mutableListOf(playableIntro("21_1_1")), introsEnabled = "false")

        verify(exactly = 0) {
            manager.loadAndPlayIntro(any(), any(), any(), any(), any(), any(), any(), any())
        }

        unmockkObject(PlayerManager)
    }

    @Test
    fun `switching intros off also stops the set-up nudge`() {
        // A server that doesn't want the feature shouldn't be recruiting
        // people into it.
        joinWithIntros(22L, mutableListOf(), introsEnabled = "false")

        verify(exactly = 0) { introHelper.promptUserForMusicInfo(any(), any()) }

        unmockkObject(PlayerManager)
    }

    @Test
    fun `an exempt voice channel stays silent`() {
        val manager = joinWithIntros(
            23L,
            mutableListOf(playableIntro("23_1_1")),
            excludedChannels = "500",
            channelId = 500L,
        )

        verify(exactly = 0) {
            manager.loadAndPlayIntro(any(), any(), any(), any(), any(), any(), any(), any())
        }

        unmockkObject(PlayerManager)
    }

    @Test
    fun `exempting one channel does not silence the others`() {
        val manager = joinWithIntros(
            24L,
            mutableListOf(playableIntro("24_1_1")),
            excludedChannels = "999",
            channelId = 500L,
        )

        verify(exactly = 1) {
            manager.loadAndPlayIntro(any(), any(), any(), any(), any(), any(), any(), any())
        }

        unmockkObject(PlayerManager)
    }

    @Test
    fun `a guild with no intro config behaves exactly as before`() {
        val manager = joinWithIntros(25L, mutableListOf(playableIntro("25_1_1")))

        verify(exactly = 1) {
            manager.loadAndPlayIntro(any(), any(), any(), any(), any(), any(), any(), any())
        }

        unmockkObject(PlayerManager)
    }

    // --- loudness normalisation --------------------------------------------

    @Test
    fun `a loud intro is turned down when loudness matching is on`() {
        val manager = joinWithIntros(
            26L,
            mutableListOf(playableIntro("26_1_1", measured = IntroLoudness.TARGET_RMS * 2)),
        )

        val volume = slot<Int>()
        verify {
            manager.loadAndPlayIntro(any(), any(), any(), any(), any(), capture(volume), any(), any())
        }
        assertTrue(volume.captured < 75, "expected the loud intro to be cut, got ${volume.captured}")

        unmockkObject(PlayerManager)
    }

    @Test
    fun `the chosen volume is used verbatim when loudness matching is off`() {
        val manager = joinWithIntros(
            27L,
            mutableListOf(playableIntro("27_1_1", measured = IntroLoudness.TARGET_RMS * 2)),
            normalise = "false",
        )

        verify {
            manager.loadAndPlayIntro(any(), any(), any(), any(), any(), 75, any(), any())
        }

        unmockkObject(PlayerManager)
    }

    @Test
    fun `an intro that has never been measured plays at exactly its set volume`() {
        // Every existing row starts unmeasured, so shipping normalisation must
        // not change what anyone hears until their intro has played once.
        val manager = joinWithIntros(28L, mutableListOf(playableIntro("28_1_1", measured = null)))

        verify {
            manager.loadAndPlayIntro(any(), any(), any(), any(), any(), 75, any(), any())
        }

        unmockkObject(PlayerManager)
    }
}
