package toby.handler

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.managers.AudioManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import toby.helpers.IntroHelper
import toby.helpers.MusicPlayerHelper.playUserIntro
import toby.helpers.UserDtoHelper
import toby.helpers.UserDtoHelper.Companion.getRequestingUserDto
import toby.jpa.dto.ConfigDto
import toby.jpa.dto.UserDto
import toby.jpa.service.IConfigService
import toby.lavaplayer.PlayerManager
import toby.logging.DiscordLogger
import java.util.concurrent.ConcurrentHashMap

private const val teamRegex = "(?i)team\\s[0-9]+"

@Service
class VoiceEventHandler @Autowired constructor(
    private val jda: JDA,
    private val configService: IConfigService,
    private val userDtoHelper: UserDtoHelper,
    private val introHelper: IntroHelper
) : ListenerAdapter() {

    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    override fun onReady(event: ReadyEvent) {
        event.jda.guildCache.forEach {
            logger.setGuildContext(it)
            it.connectToMostPopulatedVoiceChannel()
        }
    }

    private fun Guild.connectToMostPopulatedVoiceChannel() {
        val mostPopulatedChannel = this.voiceChannels
            .filter { channel -> channel.members.any { !it.user.isBot } }
            .maxByOrNull { channel -> channel.members.count { !it.user.isBot } }

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
        val tobyBot = jda.selfUser
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
            audioManager.checkAudioManagerToCloseConnectionOnEmptyChannel()
            val defaultVolume = getConfigValue(ConfigDto.Configurations.VOLUME.configValue, guild.id)
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
        val defaultVolume = getConfigValue(ConfigDto.Configurations.VOLUME.configValue, guild.id)
        val deleteDelayConfig =
            configService.getConfigByName(ConfigDto.Configurations.DELETE_DELAY.configValue, guild.id)

        checkStateAndConnectToVoiceChannel(event, audioManager, guild, defaultVolume)

        val requestingUserDto = event.member.getRequestingUserDto(userDtoHelper)
        if (audioManager.connectedChannel == event.channelJoined) {
            logger.info { "AudioManager channel and event joined channel are the same" }
            setupAndPlayUserIntro(event, guild, deleteDelayConfig, requestingUserDto)
        }
        if (requestingUserDto.musicDtos.isEmpty() && event.member.user.idLong != jda.selfUser.idLong) {
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
        if (event.member.user.idLong != jda.selfUser.idLong) {
            val joinedChannelConnectedMembers = event.channelJoined?.members?.filter { !it.user.isBot } ?: emptyList()
            if (joinedChannelConnectedMembers.isNotEmpty() && !audioManager.isConnected) {
                logger.info { "Joining new channel '${event.channelJoined?.name}'." }
                PlayerManager.instance.getMusicManager(guild).audioPlayer.volume = defaultVolume
                audioManager.openAudioConnection(event.channelJoined)
                lastConnectedChannel[guild.idLong] = event.channelJoined!!.asVoiceChannel()
            }
        }
    }

    private fun setupAndPlayUserIntro(
        event: GuildVoiceUpdateEvent, guild: Guild, deleteDelayConfig: ConfigDto?, requestingUserDto: UserDto
    ) {
        val member = event.member
        if (requestingUserDto.musicDtos.isNotEmpty()) {
            logger.info { "User has musicDto associated with them, preparing to play intro" }
            playUserIntro(
                requestingUserDto,
                guild,
                deleteDelay = deleteDelayConfig?.value?.toInt() ?: 0,
                member = member
            )
        } else {
            logger.info { "User has no musicDto associated with them, no intro will be played" }
        }
    }

    private fun onGuildVoiceLeave(event: GuildVoiceUpdateEvent) {
        val guild = event.guild
        val audioManager = guild.audioManager
        audioManager.checkAudioManagerToCloseConnectionOnEmptyChannel()
        val channelLeft = event.channelLeft
        if (channelLeft != null) {
            deleteTemporaryChannelIfEmpty(channelLeft.members.none { !it.user.isBot }, channelLeft)
        }
    }

    private fun AudioManager.checkAudioManagerToCloseConnectionOnEmptyChannel() {
        val connectedChannel = this.connectedChannel
        if (connectedChannel != null) {
            if (connectedChannel.members.none { !it.user.isBot }) {
                this.closeAudioConnection()
                logger.info("Audio connection closed due to empty channel.")
                lastConnectedChannel.remove(this.guild.idLong)
            }
        }
    }

    private fun deleteTemporaryChannelIfEmpty(nonBotConnectedMembersEmpty: Boolean, channelLeft: AudioChannel) {
        if (channelLeft.name.matches(teamRegex.toRegex()) && nonBotConnectedMembersEmpty) {
            logger.info { "Deleting temporary channel: '${channelLeft.name}'" }
            channelLeft.delete().queue()
        }
    }

    private fun getConfigValue(configName: String, guildId: String, defaultValue: Int = 100): Int {
        val config = configService.getConfigByName(configName, guildId)
        return config?.value?.toInt() ?: defaultValue
    }

    companion object {
        var lastConnectedChannel = ConcurrentHashMap<Long, VoiceChannel>()
    }
}
