package bot.toby.handler

import bot.toby.helpers.IntroHelper
import bot.toby.helpers.MusicPlayerHelper.playUserIntro
import bot.toby.helpers.UserDtoHelper
import bot.toby.helpers.UserDtoHelper.Companion.getRequestingUserDto
import bot.toby.helpers.nonBots
import bot.toby.lavaplayer.PlayerManager
import bot.toby.voice.VoiceCompanyTracker
import bot.toby.voice.VoiceCreditAwardService
import common.logging.DiscordLogger
import database.dto.ConfigDto.Configurations.DELETE_DELAY
import database.dto.ConfigDto.Configurations.VOLUME
import database.dto.VoiceSessionDto
import database.service.ConfigService
import database.service.SocialCreditAwardService
import database.service.VoiceSessionService
import bot.toby.helpers.MusicPlayerHelper
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.managers.AudioManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private const val TEAM_REGEX = "(?i)team\\s[0-9]+"

@Service
class VoiceEventHandler @Autowired constructor(
    private val configService: ConfigService,
    private val userDtoHelper: UserDtoHelper,
    private val introHelper: IntroHelper,
    private val voiceSessionService: VoiceSessionService,
    private val voiceCompanyTracker: VoiceCompanyTracker,
    private val voiceCreditAwardService: VoiceCreditAwardService,
    private val awardService: SocialCreditAwardService
) : ListenerAdapter() {

    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    override fun onReady(event: ReadyEvent) {
        val now = Instant.now()
        event.jda.guildCache.forEach { guild ->
            logger.setGuildContext(guild)
            // Reconcile voice state: any non-bot member already in a voice
            // channel needs an open voice_session row, otherwise their
            // eventual leave event has nothing to close and the post-restart
            // span gets silently dropped. The recovery hook just closed any
            // *pre-restart* sessions; this opens fresh ones from this moment.
            var reopened = 0
            guild.voiceChannels.forEach { channel ->
                channel.members
                    .filter { !it.user.isBot }
                    .forEach { member ->
                        runCatching { openSessionForUser(member.idLong, guild.idLong, channel, now) }
                            .onSuccess { reopened++ }
                            .onFailure {
                                logger.error(
                                    "Failed to open startup voice session for user ${member.idLong} " +
                                            "in channel ${channel.idLong}: ${it.message}"
                                )
                            }
                    }
            }
            // Always log the count, even when zero — silence is ambiguous, and
            // we need to be able to confirm in production logs whether the
            // reconciliation loop actually ran for this guild.
            logger.info { "Startup voice reconciliation: opened $reopened session(s) in guild ${guild.idLong}." }
            guild.connectToMostPopulatedVoiceChannel()
        }
    }

    override fun onGuildLeave(event: GuildLeaveEvent) {
        val guildId = event.guild.idLong
        logger.info { "Guild $guildId left — cleaning up audio resources" }
        PlayerManager.instance.destroyMusicManager(guildId)
        MusicPlayerHelper.nowPlayingManager.resetNowPlayingMessage(guildId)
    }

    private fun Guild.connectToMostPopulatedVoiceChannel() {
        val mostPopulatedChannel = this.voiceChannels
            .filter { channel -> channel.members.nonBots().isNotEmpty() }
            .maxByOrNull { channel -> channel.members.nonBots().size }

        mostPopulatedChannel?.checkStateAndConnectToVoiceChannel()
            ?: logger.info("No occupied voice channel to join found")
    }

    private fun VoiceChannel.checkStateAndConnectToVoiceChannel() {
        val guild = this.guild
        val audioManager = guild.audioManager
        if (!audioManager.isConnected) {
            audioManager.openAudioConnection(this)
            logger.info { "Connected to voice channel: ${this.name}" }
        }
    }

    override fun onGuildVoiceUpdate(event: GuildVoiceUpdateEvent) {
        logger.setGuildAndMemberContext(event.guild, event.member)
        when {
            event.channelJoined != null && event.channelLeft != null -> {
                logVoiceMove(event)
                onGuildVoiceMove(event)
            }

            event.channelJoined != null -> {
                logVoiceJoin(event)
                onGuildVoiceJoin(event)
            }

            event.channelLeft != null -> {
                logVoiceLeave(event)
                onGuildVoiceLeave(event)
            }
        }
    }

    private fun logVoiceMove(event: GuildVoiceUpdateEvent) {
        logger.info { "Voice move event triggered from channel ${event.channelLeft} to channel ${event.channelJoined}" }
    }

    private fun logVoiceJoin(event: GuildVoiceUpdateEvent) {
        logger.info { "Voice join event triggered in channel ${event.channelJoined}" }
    }

    private fun logVoiceLeave(event: GuildVoiceUpdateEvent) {
        logger.info { "Voice leave event triggered from channel ${event.channelLeft}" }
    }

    private fun onGuildVoiceMove(event: GuildVoiceUpdateEvent) {
        val tobyBot = event.jda.selfUser
        val member = event.member
        val guild = event.guild
        val audioManager = guild.audioManager

        // Check if the bot is being moved
        if (member.user.idLong == tobyBot.idLong) {
            logger.info { "${tobyBot.name} has been moved, checking for previous channel to rejoin..." }
            val previousChannel = lastConnectedChannel[guild.idLong]
            if (previousChannel != null) {
                logger.info { "Rejoining '${previousChannel.name}'" }
                audioManager.rejoin(previousChannel)
            } else {
                logger.warn("Bot was moved but no previous channel found.")
            }
        } else {
            logger.info { "User '${member.effectiveName}' has moved, checking if AudioConnection should close..." }
            val now = Instant.now()
            closeSessionForUser(member.idLong, guild.idLong, now)
            event.channelLeft?.let { voiceCompanyTracker.reconcileChannel(it, now) }
            event.channelJoined?.let { openSessionForUser(member.idLong, guild.idLong, it, now) }
            audioManager.checkAudioManagerToCloseConnectionOnEmptyChannel()
            val defaultVolume = getConfigValue(VOLUME.configValue, guild.id)
            checkStateAndConnectToVoiceChannel(event, audioManager, guild, defaultVolume)
        }
    }


    private fun AudioManager.rejoin(channel: VoiceChannel) {
        runCatching {
            this.openAudioConnection(channel)
            logger.info("Rejoined previous channel '${channel.name}'")
        }.onFailure {
            logger.error("Failed to rejoin channel '${channel.name}': ${it.message}")
        }
    }

    private fun onGuildVoiceJoin(event: GuildVoiceUpdateEvent) {
        val guild = event.guild
        val audioManager = guild.audioManager
        val defaultVolume = getConfigValue(VOLUME.configValue, guild.id)
        val deleteDelay =
            getConfigValue(DELETE_DELAY.configValue, guild.id, 5)

        checkStateAndConnectToVoiceChannel(event, audioManager, guild, defaultVolume)

        if (event.member.user.idLong != event.jda.selfUser.idLong) {
            val now = Instant.now()
            event.channelJoined?.let { openSessionForUser(event.member.idLong, guild.idLong, it, now) }
        }

        val requestingUserDto = event.member.getRequestingUserDto(userDtoHelper)
        if (audioManager.connectedChannel == event.channelJoined) {
            logger.info { "AudioManager channel and event joined channel are the same" }
            setupAndPlayUserIntro(event, guild, deleteDelay, requestingUserDto)
        }
        if (requestingUserDto.musicDtos.isEmpty() && event.member.user.idLong != event.jda.selfUser.idLong) {
            logger.info { "Prompting user to set an intro ..." }
            introHelper.promptUserForMusicInfo(event.member.user, guild)
        }
    }

    private fun checkStateAndConnectToVoiceChannel(
        event: GuildVoiceUpdateEvent,
        audioManager: AudioManager,
        guild: Guild,
        defaultVolume: Int
    ) {
        //Ignore the bot joining voice event
        if (event.member.user.idLong != event.jda.selfUser.idLong) {
            val joinedChannelConnectedMembers = event.channelJoined?.members?.nonBots() ?: emptyList()
            if (joinedChannelConnectedMembers.isNotEmpty() && !audioManager.isConnected) {
                logger.info { "Joining new channel '${event.channelJoined?.name}'." }
                PlayerManager.instance.getMusicManager(guild).audioPlayer.volume = defaultVolume
                event.channelJoined?.let { audioManager.openAudioConnection(it) }
                lastConnectedChannel[guild.idLong] = event.channelJoined!!.asVoiceChannel()
            }
        }
    }

    private fun setupAndPlayUserIntro(
        event: GuildVoiceUpdateEvent,
        guild: Guild,
        deleteDelay: Int,
        requestingUserDto: database.dto.UserDto
    ) {
        val member = event.member
        if (requestingUserDto.musicDtos.isNotEmpty()) {
            logger.info { "User has musicDto associated with them, preparing to play intro" }
            playUserIntro(
                requestingUserDto,
                guild,
                deleteDelay = deleteDelay,
                member = member
            )
            awardService.award(
                discordId = requestingUserDto.discordId,
                guildId = requestingUserDto.guildId,
                amount = INTRO_PLAY_CREDIT,
                reason = "intro-play"
            )
        } else {
            logger.info { "User has no musicDto associated with them, no intro will be played" }
        }
    }

    private fun onGuildVoiceLeave(event: GuildVoiceUpdateEvent) {
        val guild = event.guild
        val audioManager = guild.audioManager
        val member = event.member
        if (member.user.idLong != event.jda.selfUser.idLong) {
            val now = Instant.now()
            closeSessionForUser(member.idLong, guild.idLong, now)
            event.channelLeft?.let { voiceCompanyTracker.reconcileChannel(it, now) }
        }
        audioManager.checkAudioManagerToCloseConnectionOnEmptyChannel()
        event.channelLeft?.let { deleteTemporaryChannelIfEmpty(it.members.nonBots().isEmpty(), it) }
    }

    private fun openSessionForUser(userId: Long, guildId: Long, channel: AudioChannel, now: Instant) {
        runCatching {
            voiceSessionService.findOpenSession(userId, guildId)?.let { stale ->
                val companySeconds = voiceCompanyTracker.stopTracking(userId, guildId, now)
                voiceCreditAwardService.closeSessionAndAward(stale, now, companySeconds)
            }
            voiceCompanyTracker.reconcileChannel(channel, now)
            val session = VoiceSessionDto(
                discordId = userId,
                guildId = guildId,
                channelId = channel.idLong,
                joinedAt = now
            )
            voiceSessionService.openSession(session)
            voiceCompanyTracker.startTracking(userId, guildId, channel, now)
        }.onFailure { logger.error("Failed to open voice session for user $userId: ${it.message}") }
    }

    private fun closeSessionForUser(userId: Long, guildId: Long, now: Instant) {
        runCatching {
            val open = voiceSessionService.findOpenSession(userId, guildId) ?: return
            val companySeconds = voiceCompanyTracker.stopTracking(userId, guildId, now)
            voiceCreditAwardService.closeSessionAndAward(open, now, companySeconds)
        }.onFailure { logger.error("Failed to close voice session for user $userId: ${it.message}") }
    }

    private fun AudioManager.checkAudioManagerToCloseConnectionOnEmptyChannel() {
        val connectedChannel = this.connectedChannel
        if (connectedChannel != null) {
            if (connectedChannel.members.nonBots().isEmpty()) {
                this.closeAudioConnection()
                logger.info("Audio connection closed due to empty channel.")
                lastConnectedChannel.remove(this.guild.idLong)
            }
        }
    }

    private fun deleteTemporaryChannelIfEmpty(nonBotConnectedMembersEmpty: Boolean, channelLeft: AudioChannel) {
        if (channelLeft.name.matches(TEAM_REGEX.toRegex()) && nonBotConnectedMembersEmpty) {
            logger.info { "Deleting temporary channel: '${channelLeft.name}'" }
            channelLeft.delete().queue()
        }
    }

    private fun getConfigValue(configName: String, guildId: String, defaultValue: Int = 100): Int {
        val config = configService.getConfigByName(configName, guildId)
        return config?.value?.toInt() ?: defaultValue
    }

    companion object {
        val lastConnectedChannel = ConcurrentHashMap<Long, VoiceChannel>()

        // Small, daily-capped reward so joining a channel with an intro set
        // feels like something without becoming farmable via rejoin spam.
        const val INTRO_PLAY_CREDIT: Long = 2L
    }
}
