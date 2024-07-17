package toby.handler

import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
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
import toby.jpa.service.*
import toby.lavaplayer.PlayerManager
import toby.managers.CommandManager
import toby.managers.MenuManager
import java.util.*
import java.util.concurrent.CompletableFuture
import javax.annotation.Nonnull

@Service
@Configurable
class Handler @Autowired constructor(
    private val configService: IConfigService,
    brotherService: IBrotherService,
    private val userService: IUserService,
    musicFileService: IMusicFileService,
    excuseService: IExcuseService
) : ListenerAdapter() {

    private val commandManager =
        CommandManager(configService, brotherService, userService, musicFileService, excuseService)

    private val menuManager = MenuManager(configService)

    override fun onReady(@Nonnull event: ReadyEvent) {
        LOGGER.info("{} is ready", event.jda.selfUser.name)
    }

    override fun onMessageReceived(@Nonnull event: MessageReceivedEvent) {
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
                channel.sendMessageFormat("%s... that's not my name %s", member?.effectiveName ?: author.name, tobyEmote).queue()
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
            onGuildVoiceMove(event)
        }
    }

    private fun onGuildVoiceJoin(event: GuildVoiceUpdateEvent) {
        val guild = event.guild
        val audioManager = guild.audioManager
        val volumePropertyName = ConfigDto.Configurations.VOLUME.configValue
        val databaseVolumeConfig = configService.getConfigByName(volumePropertyName, event.guild.id)
        val deleteDelayConfig = configService.getConfigByName(ConfigDto.Configurations.DELETE_DELAY.configValue, event.guild.id)

        val defaultVolume = databaseVolumeConfig?.value?.toInt() ?: 100
        val nonBotConnectedMembers = event.channelJoined?.members?.filter { !it.user.isBot } ?: emptyList()

        checkCurrentAudioManagerForNonBotMembers(audioManager)
        if (nonBotConnectedMembers.isNotEmpty() && !audioManager.isConnected) {
            PlayerManager.instance.getMusicManager(guild).audioPlayer.volume = defaultVolume
            audioManager.openAudioConnection(event.channelJoined!!)
        }

        val member = event.member
        val discordId = member.user.idLong
        val guildId = guild.idLong
        val requestingUserDto = calculateUserDto(guildId, discordId, member.isOwner, userService, defaultVolume)

        if (audioManager.connectedChannel == event.channelJoined) {
            playUserIntro(requestingUserDto, guild, deleteDelayConfig?.value?.toInt() ?: 0, 0L)
        }
    }

    private fun onGuildVoiceMove(event: GuildVoiceUpdateEvent) {
        val guild = event.guild
        val audioManager = guild.audioManager
        val volumePropertyName = ConfigDto.Configurations.VOLUME.configValue
        val databaseConfig = configService.getConfigByName(volumePropertyName, event.guild.id)
        val defaultVolume = databaseConfig?.value?.toInt() ?: 100
        closeEmptyAudioManagerAndRejoin(event, guild, audioManager, defaultVolume)
    }

    private fun closeEmptyAudioManagerAndRejoin(
        event: GuildVoiceUpdateEvent, guild: Guild, audioManager: AudioManager, defaultVolume: Int
    ) {
        val future = CompletableFuture.supplyAsync {
            checkCurrentAudioManagerForNonBotMembers(audioManager)
            audioManager // Supply the AudioManager instance
        }

        future.thenAcceptAsync { am: AudioManager ->
            LOGGER.info("Is connected: {}", am.isConnected)
            if (!am.isConnected) {
                LOGGER.info("Attempting to connect...")
                val nonBotConnectedMembers = event.channelJoined!!.members.filter { !it.user.isBot }
                if (nonBotConnectedMembers.isNotEmpty()) {
                    PlayerManager.instance.getMusicManager(guild).audioPlayer.volume = defaultVolume
                    am.openAudioConnection(event.channelJoined)
                    LOGGER.info("Audio connection opened.")
                }
                deleteTemporaryChannelIfEmpty(nonBotConnectedMembers.isEmpty(), event.channelLeft)
            }
        }
    }


    private fun onGuildVoiceLeave(event: GuildVoiceUpdateEvent) {
        val guild = event.guild
        val audioManager = guild.audioManager
        checkCurrentAudioManagerForNonBotMembers(audioManager)
        deleteTemporaryChannelIfEmpty(event.hasNonBotConnectedMembersInLeftChannel(), event.channelLeft)
    }

    private fun deleteTemporaryChannelIfEmpty(nonBotConnectedMembersEmpty: Boolean, channelLeft: AudioChannel?) {
        if (channelLeft?.name?.matches("(?i)team\\s[0-9]+".toRegex()) == true && nonBotConnectedMembersEmpty) {
            channelLeft.delete().queue()
        }
    }

    private fun GuildVoiceUpdateEvent.hasNonBotConnectedMembersInLeftChannel() : Boolean = this.channelLeft?.members?.none { !it.user.isBot } == true


    override fun onStringSelectInteraction(event: StringSelectInteractionEvent) {
        menuManager.handle(event)
    }

    companion object {
        private val LOGGER: Logger = LoggerFactory.getLogger(Handler::class.java)

        private fun checkCurrentAudioManagerForNonBotMembers(audioManager: AudioManager): AudioManager {
            if (audioManager.isConnected) {
                val membersInConnectedVoiceChannel = audioManager.connectedChannel?.members?.filter { !it.user.isBot } ?: emptyList()
                if (membersInConnectedVoiceChannel.isEmpty()) {
                    closeAudioPlayer(audioManager.guild, audioManager)
                }
            }
            return audioManager
        }

        private fun closeAudioPlayer(guild: Guild, audioManager: AudioManager) {
            val musicManager = PlayerManager.instance.getMusicManager(guild)
            musicManager.scheduler.isLooping = false
            musicManager.scheduler.queue.clear()
            musicManager.audioPlayer.stopTrack()
            audioManager.closeAudioConnection()
        }
    }
}