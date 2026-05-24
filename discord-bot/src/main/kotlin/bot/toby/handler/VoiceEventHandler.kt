package bot.toby.handler

import bot.toby.helpers.IntroHelper
import bot.toby.helpers.MusicPlayerHelper.playUserIntro
import bot.toby.helpers.UserDtoHelper
import bot.toby.helpers.UserDtoHelper.Companion.getRequestingUserDto
import bot.toby.helpers.nonBots
import bot.toby.lavaplayer.PlayerManager
import bot.toby.managers.NowPlayingManager
import bot.toby.voice.LastConnectedChannelTracker
import bot.toby.voice.VoiceSessionLifecycle
import common.logging.DiscordLogger
import database.dto.ConfigDto.Configurations.DELETE_DELAY
import database.dto.ConfigDto.Configurations.VOLUME
import database.service.guild.ConfigService
import database.service.social.SocialCreditAwardService
import database.service.leveling.XpAwardService
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.managers.AudioManager
import org.springframework.stereotype.Service
import java.time.Instant

private const val TEAM_REGEX = "(?i)team\\s[0-9]+"

@Service
class VoiceEventHandler(
    private val configService: ConfigService,
    private val userDtoHelper: UserDtoHelper,
    private val introHelper: IntroHelper,
    private val voiceSessionLifecycle: VoiceSessionLifecycle,
    private val lastConnectedChannelTracker: LastConnectedChannelTracker,
    private val awardService: SocialCreditAwardService,
    private val xpAwardService: XpAwardService,
    private val nowPlayingManager: NowPlayingManager,
) : ListenerAdapter() {

    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    override fun onReady(event: ReadyEvent) {
        val now = Instant.now()
        event.jda.guildCache.forEach { guild ->
            logger.setGuildContext(guild)
            // Reconcile voice state: any non-bot member already in a voice
            // channel needs an open voice_session row, otherwise their
            // eventual leave event has nothing to close and the post-restart
            // span gets silently dropped.
            var reopened = 0
            guild.voiceChannels.forEach { channel ->
                channel.members
                    .filter { !it.user.isBot }
                    .forEach { member ->
                        runCatching { voiceSessionLifecycle.openSession(member.idLong, guild.idLong, channel, now) }
                            .onSuccess { reopened++ }
                            .onFailure {
                                logger.error(
                                    "Failed to open startup voice session for user ${member.idLong} " +
                                            "in channel ${channel.idLong}: ${it.message}"
                                )
                            }
                    }
            }
            logger.info { "Startup voice reconciliation: opened $reopened session(s) in guild ${guild.idLong}." }
            guild.connectToMostPopulatedVoiceChannel()
        }
    }

    override fun onGuildLeave(event: GuildLeaveEvent) {
        val guildId = event.guild.idLong
        logger.info { "Guild $guildId left — cleaning up audio resources" }
        PlayerManager.instance.destroyMusicManager(guildId)
        nowPlayingManager.resetNowPlayingMessage(guildId)
        // The bot is no longer in this guild; the cached channel reference is
        // dead weight (and pins a stale JDA VoiceChannel via id alone is moot
        // here since we only kept ids — but the entry is still useless).
        lastConnectedChannelTracker.clear(guildId)
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

        if (member.user.idLong == tobyBot.idLong) {
            logger.info { "${tobyBot.name} has been moved, checking for previous channel to rejoin..." }
            val previousChannel = lastConnectedChannelTracker.resolveChannel(guild)
            if (previousChannel != null) {
                logger.info { "Rejoining '${previousChannel.name}'" }
                audioManager.rejoin(previousChannel)
            } else {
                logger.warn("Bot was moved but no previous channel found.")
            }
        } else {
            logger.info { "User '${member.effectiveName}' has moved, checking if AudioConnection should close..." }
            val now = Instant.now()
            voiceSessionLifecycle.closeSession(member.idLong, guild.idLong, event.channelLeft, now)
            event.channelJoined?.let { voiceSessionLifecycle.openSession(member.idLong, guild.idLong, it, now) }
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
            event.channelJoined?.let { voiceSessionLifecycle.openSession(event.member.idLong, guild.idLong, it, now) }
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
        if (event.member.user.idLong != event.jda.selfUser.idLong) {
            val joinedChannelConnectedMembers = event.channelJoined?.members?.nonBots() ?: emptyList()
            if (joinedChannelConnectedMembers.isNotEmpty() && !audioManager.isConnected) {
                logger.info { "Joining new channel '${event.channelJoined?.name}'." }
                PlayerManager.instance.getMusicManager(guild).audioPlayer.volume = defaultVolume
                event.channelJoined?.let { joined ->
                    audioManager.openAudioConnection(joined)
                    lastConnectedChannelTracker.set(guild.idLong, joined.idLong)
                }
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
            xpAwardService.award(
                discordId = requestingUserDto.discordId,
                guildId = requestingUserDto.guildId,
                amount = INTRO_PLAY_XP,
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
            voiceSessionLifecycle.closeSession(member.idLong, guild.idLong, event.channelLeft, now)
        }
        audioManager.checkAudioManagerToCloseConnectionOnEmptyChannel()
        event.channelLeft?.let { deleteTemporaryChannelIfEmpty(it.members.nonBots().isEmpty(), it) }
    }

    private fun AudioManager.checkAudioManagerToCloseConnectionOnEmptyChannel() {
        val connectedChannel = this.connectedChannel
        if (connectedChannel != null) {
            if (connectedChannel.members.nonBots().isEmpty()) {
                this.closeAudioConnection()
                logger.info("Audio connection closed due to empty channel.")
                lastConnectedChannelTracker.clear(this.guild.idLong)
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
        // Small, daily-capped reward so joining a channel with an intro set
        // feels like something without becoming farmable via rejoin spam.
        const val INTRO_PLAY_CREDIT: Long = 2L

        // XP grant for the same intro-play event. Slightly larger than the
        // credit grant since the XP daily cap is much higher; this still
        // sits well under the cap so a few intro plays in a day stack.
        const val INTRO_PLAY_XP: Long = 10L
    }
}
