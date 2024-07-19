package toby.handler

import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion
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
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Configurable
import org.springframework.stereotype.Service
import toby.BotMain
import toby.emote.Emotes
import toby.helpers.MusicPlayerHelper.playUserIntro
import toby.helpers.UserDtoHelper.calculateUserDto
import toby.jpa.dto.ConfigDto
import toby.jpa.dto.UserDto
import toby.jpa.service.*
import toby.lavaplayer.PlayerManager
import toby.managers.CommandManager
import toby.managers.MenuManager
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.annotation.Nonnull

@Service
@Configurable
class Handler @Autowired constructor(
    private val configService: IConfigService,
    brotherService: IBrotherService,
    private val userService: IUserService,
    musicFileService: IMusicFileService,
    excuseService: IExcuseService,
) : ListenerAdapter() {

    private val commandManager = CommandManager(configService, brotherService, userService, musicFileService, excuseService)
    private val menuManager = MenuManager(configService)

    override fun onReady(@Nonnull event: ReadyEvent) {
        LOGGER.info("${event.jda.selfUser.name} is ready")
    }

    override fun onMessageReceived(event: MessageReceivedEvent) {
        val message = event.message
        val author = event.author
        val channel = event.channel
        val guild = event.guild
        val member = event.member

        if (author.isBot || event.isWebhookMessage) {
            return
        }

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
                channel.sendMessageFormat("Hey %s, what's up champ?", member?.effectiveName ?: author.name).queue()
                channel.sendMessage(jessEmote.toString()).queue()
            }

            messageStringLowercase.contains("yeah") -> {
                channel.sendMessage("YEAH????").queue()
            }

            BotMain.jda?.selfUser?.let { message.mentions.isMentioned(it) } == true -> {
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
            commandManager.handle(event)
        }
    }

    override fun onGuildReady(event: GuildReadyEvent) {
        event.guild.updateCommands().addCommands(commandManager.allSlashCommands).queue()
    }

    override fun onGuildJoin(event: GuildJoinEvent) {
        event.guild.updateCommands().addCommands(commandManager.allSlashCommands).queue()
    }

    override fun onGuildAvailable(event: GuildAvailableEvent) {
        event.guild.updateCommands().addCommands(commandManager.allSlashCommands).queue()
    }

    override fun onGuildVoiceUpdate(event: GuildVoiceUpdateEvent) {
        val guildId = event.guild.id

        when {
            event.channelJoined != null && event.channelLeft != null -> {
                LOGGER.info("Voice move event triggered for guild $guildId from channel ${event.channelLeft} to channel ${event.channelJoined}")
                onGuildVoiceMove(event.guild)
            }

            event.channelJoined != null -> {
                LOGGER.info("Voice join event triggered for guild $guildId in channel ${event.channelJoined}")
                onGuildVoiceJoin(event)
            }

            event.channelLeft != null -> {
                LOGGER.info("Voice leave event triggered for guild $guildId from channel ${event.channelLeft}")
                onGuildVoiceLeave(event)
            }
        }
    }

    private fun onGuildVoiceMove(guild: Guild) {
        lastConnectedChannel[guild.idLong]?.let { rejoinPreviousChannel(guild, it) }
    }

    private fun rejoinPreviousChannel(guild: Guild, channel: AudioChannelUnion) {
        guild.audioManager.openAudioConnection(channel)
        LOGGER.info("Rejoined previous channel '${channel.name}' on guild '${guild.id}'")
        lastConnectedChannel.remove(channel.idLong)
    }

    private fun onGuildVoiceJoin(event: GuildVoiceUpdateEvent) {
        val guild = event.guild
        val audioManager = guild.audioManager
        val defaultVolume = getConfigValue(ConfigDto.Configurations.VOLUME.configValue, guild.id)
        val deleteDelayConfig = configService.getConfigByName(ConfigDto.Configurations.DELETE_DELAY.configValue, guild.id)

        val nonBotConnectedMembers = event.channelJoined?.members?.filter { !it.user.isBot } ?: emptyList()

        if (nonBotConnectedMembers.isNotEmpty() && !audioManager.isConnected) {
            PlayerManager.instance.getMusicManager(guild).audioPlayer.volume = defaultVolume
            audioManager.openAudioConnection(event.channelJoined)
            lastConnectedChannel[guild.idLong] = event.channelJoined!!
        }

        setupAndPlayUserIntro(event.member, guild, defaultVolume, deleteDelayConfig)
    }

    private fun setupAndPlayUserIntro(
        member: Member,
        guild: Guild,
        defaultVolume: Int,
        deleteDelayConfig: ConfigDto?
    ) {
        val requestingUserDto = getRequestingUserDto(member, defaultVolume)
        playUserIntro(requestingUserDto, guild, deleteDelayConfig?.value?.toInt() ?: 0)
    }

    private fun onGuildVoiceLeave(event: GuildVoiceUpdateEvent) {
        val guild = event.guild
        val audioManager = guild.audioManager
        checkCurrentAudioManagerForNonBotMembers(audioManager)
        deleteTemporaryChannelIfEmpty(event.channelLeft?.members?.none { !it.user.isBot } ?: true, event.channelLeft)
    }

    private fun checkCurrentAudioManagerForNonBotMembers(audioManager: AudioManager) {
        val connectedChannel = audioManager.connectedChannel
        if (connectedChannel != null) {
            if (connectedChannel.members.none { !it.user.isBot }) {
                audioManager.closeAudioConnection()
                LOGGER.info("Audio connection closed on guild ${audioManager.guild.id} due to empty channel.")
                lastConnectedChannel.remove(audioManager.guild.idLong)
            }
        }
    }

    private fun deleteTemporaryChannelIfEmpty(nonBotConnectedMembersEmpty: Boolean, channelLeft: AudioChannel?) {
        if (channelLeft?.name?.matches("(?i)team\\s[0-9]+".toRegex()) == true && nonBotConnectedMembersEmpty) {
            LOGGER.info("Deleting temporary channel: {}", channelLeft.name)
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
        menuManager.handle(event)
    }

    companion object {
        val LOGGER: Logger = LoggerFactory.getLogger(Handler::class.java)
        var lastConnectedChannel = ConcurrentHashMap<Long, AudioChannelUnion>()
    }
}
