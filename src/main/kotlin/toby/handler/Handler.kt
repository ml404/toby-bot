package toby.handler

import kotlinx.coroutines.*
import net.dv8tion.jda.api.entities.Guild
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
import javax.annotation.Nonnull
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Service
@Configurable
class Handler @Autowired constructor(
    private val configService: IConfigService,
    brotherService: IBrotherService,
    private val userService: IUserService,
    musicFileService: IMusicFileService,
    excuseService: IExcuseService,
    private val logger: Logger = LoggerFactory.getLogger(Handler::class.java)
) : ListenerAdapter(), CoroutineScope {

    private val commandManager = CommandManager(configService, brotherService, userService, musicFileService, excuseService)
    private val menuManager = MenuManager(configService)
    private val job = Job()
    override val coroutineContext = Dispatchers.Default + job

    override fun onReady(@Nonnull event: ReadyEvent) {
        logger.info("${event.jda.selfUser.name} is ready")
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
        if (event.channelJoined != null) {
            onGuildVoiceJoin(event)
        }
        if (event.channelLeft != null) {
            onGuildVoiceLeave(event)
        }
        if (event.channelJoined == null && event.channelLeft == null) {
            onGuildVoiceMove(event.guild)
        }
    }

    private fun onGuildVoiceMove(guild: Guild) {
        val audioManager = guild.audioManager
        val defaultVolume = getConfigValue(ConfigDto.Configurations.VOLUME.configValue, guild.id)
        val deleteDelayConfig = configService.getConfigByName(ConfigDto.Configurations.DELETE_DELAY.configValue, guild.id)

        // Launch a coroutine to handle the voice move without blocking the main thread
        launch(Dispatchers.IO) {
            val nextChannelJoined = waitForNextChannelJoined(guild)
            if (audioManager.connectedChannel?.members?.none { !it.user.isBot } == true) {
                val nonBotConnectedMembers = nextChannelJoined.members.filter { !it.user.isBot }
                if (nonBotConnectedMembers.isNotEmpty() && !audioManager.isConnected) {
                    PlayerManager.instance.getMusicManager(guild).audioPlayer.volume = defaultVolume
                    audioManager.openAudioConnection(nextChannelJoined)
                    logger.info("Audio connection opened.")
                }
                setupAndPlayUserIntro(guild, defaultVolume, audioManager, nextChannelJoined, deleteDelayConfig)
            }
        }
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
        }

        setupAndPlayUserIntro(guild, defaultVolume, audioManager, event.channelJoined!!, deleteDelayConfig)
    }

    private suspend fun waitForNextChannelJoined(guild: Guild): AudioChannel = withContext(Dispatchers.IO) {
        suspendCoroutine { continuation ->
            val listener = object : ListenerAdapter() {
                override fun onGuildVoiceUpdate(event: GuildVoiceUpdateEvent) {
                    if (event.guild == guild && event.channelJoined != null) {
                        guild.jda.removeEventListener(this)
                        continuation.resume(event.channelJoined!!)
                    }
                }
            }
            guild.jda.addEventListener(listener)
        }
    }

    private fun setupAndPlayUserIntro(
        guild: Guild,
        defaultVolume: Int,
        audioManager: AudioManager,
        nextChannelJoined: AudioChannel,
        deleteDelayConfig: ConfigDto?
    ) {
        val requestingUserDto = getRequestingUserDto(guild, defaultVolume)
        if (audioManager.connectedChannel == nextChannelJoined) {
            playUserIntro(requestingUserDto, guild, deleteDelayConfig?.value?.toInt() ?: 0)
        }
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
                logger.info("Audio connection closed due to empty channel.")
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

    private fun getRequestingUserDto(guild: Guild, defaultVolume: Int): UserDto {
        val member = guild.selfMember // Assuming you're using the bot's self member
        val discordId = member.idLong
        val guildId = guild.idLong
        return calculateUserDto(guildId, discordId, member.isOwner, userService, defaultVolume)
    }

    override fun onStringSelectInteraction(event: StringSelectInteractionEvent) {
        menuManager.handle(event)
    }

}
