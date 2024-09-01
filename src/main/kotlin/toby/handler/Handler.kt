package toby.handler

import mu.KotlinLogging
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel
import net.dv8tion.jda.api.events.guild.GuildAvailableEvent
import net.dv8tion.jda.api.events.guild.GuildJoinEvent
import net.dv8tion.jda.api.events.guild.GuildReadyEvent
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.managers.AudioManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Configurable
import org.springframework.stereotype.Service
import toby.BotMain.Companion.jda
import toby.emote.Emotes
import toby.helpers.HttpHelper
import toby.helpers.MusicPlayerHelper.playUserIntro
import toby.helpers.UserDtoHelper.calculateUserDto
import toby.jpa.dto.ConfigDto
import toby.jpa.dto.UserDto
import toby.jpa.service.*
import toby.lavaplayer.PlayerManager
import toby.managers.ButtonManager
import toby.managers.CommandManager
import toby.managers.MenuManager
import java.util.*

@Service
@Configurable
class Handler @Autowired constructor(
    private val configService: IConfigService,
    brotherService: IBrotherService,
    private val userService: IUserService,
    musicFileService: IMusicFileService,
    excuseService: IExcuseService,
    httpHelper: HttpHelper,
    private val commandManager: CommandManager = CommandManager(
        configService,
        brotherService,
        userService,
        musicFileService,
        excuseService,
        httpHelper
    ),
    private val buttonManager: ButtonManager = ButtonManager(configService, userService, commandManager),
    private val menuManager: MenuManager = MenuManager(configService, httpHelper)
) : ListenerAdapter() {


    override fun onReady(event: ReadyEvent) {
        logger.info("${event.jda.selfUser.name} is ready")
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

    override fun onMessageReceived(event: MessageReceivedEvent) {
        val message = event.message
        val author = event.author
        val channel = event.channel
        val guild = event.guild
        val member = event.member

        if (author.isBot || event.isWebhookMessage) return

        val messageStringLowercase = message.contentRaw.lowercase(Locale.getDefault())

        when {
            messageStringLowercase.contains("toby") || messageStringLowercase.contains("tobs") -> {
                val tobyEmote = guild.jda.getEmojiById(Emotes.TOBY)
                channel.sendMessageFormat(
                    "%s... that's not my name %s",
                    member?.effectiveName ?: author.name,
                    tobyEmote
                ).queue()
                message.addReaction(tobyEmote!!).queue()
            }

            messageStringLowercase.trim() == "sigh" -> {
                val jessEmote = guild.jda.getEmojiById(Emotes.JESS)
                channel.sendMessageFormat("Hey %s, what's up champ?", member?.effectiveName ?: author.name, jessEmote).queue()
            }

            messageStringLowercase.contains("yeah") -> {
                channel.sendMessage("YEAH????").queue()
            }

            jda?.selfUser?.let { message.mentions.isMentioned(it) } == true -> {
                channel.sendMessage("Don't talk to me").queue()
            }
        }
    }

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        if (!event.user.isBot) {
            commandManager.handle(event)
        }
    }

    override fun onButtonInteraction(event: ButtonInteractionEvent) {
        event.deferReply(true).queue()
        if (!event.user.isBot) {
            buttonManager.handle(event)
        }
    }

    override fun onGuildReady(event: GuildReadyEvent) {
        updateCommands(event.guild)
    }

    override fun onGuildJoin(event: GuildJoinEvent) {
        updateCommands(event.guild)
    }

    override fun onGuildAvailable(event: GuildAvailableEvent) {
        updateCommands(event.guild)
    }

    private fun updateCommands(guild: Guild) {
        guild.updateCommands().addCommands(commandManager.allSlashCommands).queue()
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
        if (member.user.jda.selfUser == jda?.selfUser && !event.guild.audioManager.isConnected) {
            val previousChannel = event.channelLeft?.asVoiceChannel()
            if (previousChannel != null) {
                rejoinPreviousChannel(event.guild, previousChannel)
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

        val nonBotConnectedMembers = event.channelJoined?.members?.filter { !it.user.isBot } ?: emptyList()
        if (nonBotConnectedMembers.isNotEmpty() && !audioManager.isConnected) {
            PlayerManager.instance.getMusicManager(guild).audioPlayer.volume = defaultVolume
            audioManager.openAudioConnection(event.channelJoined)
        }

        if (audioManager.connectedChannel == event.channelJoined) {
            logger.info("Audiomanager channel ${audioManager.connectedChannel} and event joined channel ${event.channelJoined} are the same")
            setupAndPlayUserIntro(event.member, guild, defaultVolume, deleteDelayConfig)
        }
    }

    private fun setupAndPlayUserIntro(member: Member, guild: Guild, defaultVolume: Int, deleteDelayConfig: ConfigDto?) {
        val requestingUserDto = getRequestingUserDto(member, defaultVolume)
        playUserIntro(requestingUserDto, guild, deleteDelayConfig?.value?.toInt() ?: 0)
    }

    private fun onGuildVoiceLeave(event: GuildVoiceUpdateEvent) {
        val guild = event.guild
        val audioManager = guild.audioManager
        audioManager.checkAudioManagerToCloseConnectionOnEmptyChannel()
        deleteTemporaryChannelIfEmpty(event.channelLeft?.members?.none { !it.user.isBot } ?: true, event.channelLeft)
    }

    private fun AudioManager.checkAudioManagerToCloseConnectionOnEmptyChannel() {
        val connectedChannel = this.connectedChannel
        if (connectedChannel != null) {
            if (connectedChannel.members.none { !it.user.isBot }) {
                this.closeAudioConnection()
                logger.info("Audio connection closed on guild ${this.guild.id} due to empty channel.")
            }
        }
    }

    private fun deleteTemporaryChannelIfEmpty(nonBotConnectedMembersEmpty: Boolean, channelLeft: AudioChannel?) {
        if (channelLeft?.name?.matches("(?i)team\\s[0-9]+".toRegex()) == true && nonBotConnectedMembersEmpty) {
            logger.info("Deleting temporary channel: {}", channelLeft.name)
            channelLeft.delete().queue()
        }
    }

    private fun getConfigValue(configName: String, guildId: String, defaultValue: Int = 100): Int {
        val config = configService.getConfigByName(configName, guildId)
        return config?.value?.toInt() ?: defaultValue
    }

    private fun getRequestingUserDto(member: Member, defaultVolume: Int): UserDto {
        val discordId = member.idLong
        val guildId = member.guild.idLong
        return calculateUserDto(guildId, discordId, member.isOwner, userService, defaultVolume)
    }

    override fun onStringSelectInteraction(event: StringSelectInteractionEvent) {
        logger.info { "StringSelectInteractionEvent received on guild ${event.guild?.idLong}" }
        menuManager.handle(event)
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
