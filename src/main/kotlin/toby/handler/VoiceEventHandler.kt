package toby.handler

import mu.KotlinLogging
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.managers.AudioManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import toby.helpers.MusicPlayerHelper.playUserIntro
import toby.helpers.UserDtoHelper
import toby.jpa.dto.ConfigDto
import toby.jpa.dto.UserDto
import toby.jpa.service.IConfigService
import toby.lavaplayer.PlayerManager
import java.util.concurrent.ConcurrentHashMap

private const val teamRegex = "(?i)team\\s[0-9]+"

@Service
class VoiceEventHandler @Autowired constructor(
    private val jda: JDA,
    private val configService: IConfigService,
    private val userDtoHelper: UserDtoHelper
) : ListenerAdapter() {

    private val logger = KotlinLogging.logger {}

    override fun onReady(event: ReadyEvent) {
        event.jda.guildCache.forEach { it.connectToMostPopulatedVoiceChannel() }
    }

    private fun Guild.connectToMostPopulatedVoiceChannel() {
        val mostPopulatedChannel = this.voiceChannels
            .filter { channel -> channel.members.any { !it.user.isBot } }
            .maxByOrNull { channel -> channel.members.count { !it.user.isBot } }

        if (mostPopulatedChannel != null && mostPopulatedChannel.members.count { !it.user.isBot } > 0) {
            mostPopulatedChannel.connectToVoiceChannel()
        } else {
            logger.info("No occupied voice channel to join found in guild: ${this.name}")
        }
    }

    private fun VoiceChannel.connectToVoiceChannel() {
        val guild = this.guild
        val audioManager = guild.audioManager
        if (!audioManager.isConnected) {
            audioManager.openAudioConnection(this)
            logger.info { "Connected to voice channel: ${this.name} in guild: ${guild.name}" }
        }
    }

    override fun onGuildVoiceUpdate(event: GuildVoiceUpdateEvent) {
        val guildId = event.guild.id

        when {
            event.channelJoined != null && event.channelLeft != null -> {
                logger.info("Voice move event triggered for guild $guildId from channel ${event.channelLeft} to channel ${event.channelJoined} for user ${event.member.effectiveName}")
                onGuildVoiceMove(event)
            }

            event.channelJoined != null -> {
                logger.info("Voice join event triggered for guild $guildId in channel ${event.channelJoined} for user ${event.member.effectiveName}")
                onGuildVoiceJoin(event)
            }

            event.channelLeft != null -> {
                logger.info("Voice leave event triggered for guild $guildId from channel ${event.channelLeft} for user ${event.member.effectiveName}")
                onGuildVoiceLeave(event)
            }
        }
    }

    private fun onGuildVoiceMove(event: GuildVoiceUpdateEvent) {
        val member = event.member
        val guild = event.guild
        guild.audioManager.checkAudioManagerToCloseConnectionOnEmptyChannel()
        if (member.user.jda.selfUser == jda.selfUser) {
            val previousChannel = lastConnectedChannel[guild.idLong]
            if (previousChannel != null) {
                rejoinPreviousChannel(guild, previousChannel)
            } else {
                logger.warn("Bot was moved from a channel but no previous channel found.")
            }
        }
    }

    private fun rejoinPreviousChannel(guild: Guild, channel: VoiceChannel) {
        runCatching {
            guild.audioManager.openAudioConnection(channel)
            logger.info("Rejoined previous channel '${channel.name}' on guild '${guild.id}'")
        }.onFailure {
            logger.error("Failed to rejoin channel '${channel.name}' on guild '${guild.id}': ${it.message}")
        }
    }

    private fun onGuildVoiceJoin(event: GuildVoiceUpdateEvent) {
        val guild = event.guild
        val audioManager = guild.audioManager
        val defaultVolume = getConfigValue(ConfigDto.Configurations.VOLUME.configValue, guild.id)
        val deleteDelayConfig =
            configService.getConfigByName(ConfigDto.Configurations.DELETE_DELAY.configValue, guild.id)
        lastConnectedChannel[guild.idLong] = event.channelJoined!!.asVoiceChannel()

        val nonBotConnectedMembers = event.channelJoined?.members?.filter { !it.user.isBot } ?: emptyList()
        if (nonBotConnectedMembers.isNotEmpty() && !audioManager.isConnected) {
            PlayerManager.instance.getMusicManager(guild).audioPlayer.volume = defaultVolume
            audioManager.openAudioConnection(event.channelJoined)
        }

        if (audioManager.connectedChannel == event.channelJoined) {
            logger.info("Audiomanager channel ${audioManager.connectedChannel} and event joined channel ${event.channelJoined} are the same")
            setupAndPlayUserIntro(event.member, guild, deleteDelayConfig)
        }
    }

    private fun setupAndPlayUserIntro(member: Member, guild: Guild, deleteDelayConfig: ConfigDto?) {
        val requestingUserDto = getRequestingUserDto(member)
        playUserIntro(requestingUserDto, guild, deleteDelayConfig?.value?.toInt() ?: 0)
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
                logger.info("Audio connection closed on guild ${this.guild.id} due to empty channel.")
                lastConnectedChannel.remove(this.guild.idLong)
            }
        }
    }

    private fun deleteTemporaryChannelIfEmpty(nonBotConnectedMembersEmpty: Boolean, channelLeft: AudioChannel) {
        if (channelLeft.name.matches(teamRegex.toRegex()) && nonBotConnectedMembersEmpty) {
            logger.info("Deleting temporary channel: {}", channelLeft.name)
            channelLeft.delete().queue()
        }
    }

    private fun getConfigValue(configName: String, guildId: String, defaultValue: Int = 100): Int {
        val config = configService.getConfigByName(configName, guildId)
        return config?.value?.toInt() ?: defaultValue
    }

    private fun getRequestingUserDto(member: Member): UserDto {
        val discordId = member.idLong
        val guildId = member.guild.idLong
        return userDtoHelper.calculateUserDto(guildId, discordId, member.isOwner)
    }

    companion object {
        var lastConnectedChannel = ConcurrentHashMap<Long, VoiceChannel>()

    }
}
