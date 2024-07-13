package toby.handler

import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.entities.emoji.Emoji
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
import toby.lavaplayer.GuildMusicManager
import toby.lavaplayer.PlayerManager
import toby.managers.CommandManager
import toby.managers.MenuManager
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.stream.Collectors
import javax.annotation.Nonnull

@Service
@Configurable
class Handler @Autowired constructor(
    private val configService: IConfigService,
    private val brotherService: IBrotherService,
    private val userService: IUserService,
    private val musicFileService: IMusicFileService,
    private val excuseService: IExcuseService
) : ListenerAdapter() {
    private val commandManager =
        CommandManager(configService, brotherService, userService, musicFileService, excuseService)

    private val menuManager = MenuManager(configService)


    override fun onReady(@Nonnull event: ReadyEvent) {
        LOGGER.info("{} is ready", event.jda.selfUser.name)
    }

    override fun onMessageReceived(@Nonnull event: MessageReceivedEvent) {
        val user = event.author

        if (user.isBot || event.isWebhookMessage) {
            return
        }

        //Event specific information
        val author = event.author //The user that sent the message
        val message = event.message //The message that was received.
        val channel: MessageChannel = event.channel //This is the MessageChannel that the message was sent to.

        //  This could be a TextChannel, PrivateChannel, or Group!
        val msg = message.contentDisplay //This returns a human readable version of the Message. Similar to

        // what you would see in the client.
        val bot = author.isBot //This boolean is useful to determine if the User that


        // sent the Message is a BOT or not!
        if (bot) {
            return
        }
        val guild = event.guild //The Guild that this message was sent in. (note, in the API, Guilds are Servers)
        val textChannel = event.channel.asTextChannel() //The TextChannel that this message was sent to.
        val member =
            event.member //This Member that sent the message. Contains Guild specific information about the User!
        val name = if (message.isWebhookMessage) {
            author.name //If this is a Webhook message, then there is no Member associated
        } // with the User, thus we default to the author for name.
        else {
            member!!.effectiveName //This will either use the Member's nickname if they have one,
        } // otherwise it will default to their username. (User#getName())


        val jda = guild.jda
        val tobyEmote: Emoji? = jda.getEmojiById(Emotes.TOBY)
        val jessEmote: Emoji? = jda.getEmojiById(Emotes.JESS)

        messageContainsRespond(message, channel, name, tobyEmote, jessEmote)
    }

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        val user = event.user
        if (user.isBot) {
            return
        }
        commandManager.handle(event)
    }

    override fun onButtonInteraction(event: ButtonInteractionEvent) {
        event.deferReply(true).queue()
        val user = event.user
        if (user.isBot) {
            return
        }
        commandManager.handle(event)
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

    private fun messageContainsRespond(
        message: Message, channel: MessageChannel, name: String, tobyEmote: Emoji?, jessEmote: Emoji?
    ) {
        val messageStringLowercase = message.contentRaw.lowercase(Locale.getDefault())
        if (messageStringLowercase.contains("toby") || messageStringLowercase.contains("tobs")) {
            message.addReaction(tobyEmote!!).queue()
            channel.sendMessageFormat("%s... that's not my name %s", name, tobyEmote).queue()
        }

        if (messageStringLowercase.trim { it <= ' ' }.contains("sigh")) {
            channel.sendMessageFormat("Hey %s, what's up champ?", name).queue()
            channel.sendMessageFormat("%s", jessEmote).queue()
        }

        if (messageStringLowercase.contains("yeah")) {
            channel.sendMessage("YEAH????").queue()
        }

        if (BotMain.jda?.selfUser?.let { message.mentions.isMentioned(it) } == true) {
            channel.sendMessage("Don't talk to me").queue()
        }

        if (messageStringLowercase.contains("covid") || messageStringLowercase.contains("corona")) {
            channel.sendMessage(
                """
                    It is the 2nd millennium, for more than two years humanity has sat immobile on it's fat arse whilst COVID roams the planet. They are the masters of Netflix by the will of the settee, and masters of ordering Chinese takeaway through the might of their wallets. They are fattening imbeciles imbued with energy from last nights curry. They are the isolated ones for whom more than a million people wear masks every day.

                    Yet, even in their quarantined state, they continue to make everyone's lives miserable. Arsehole scalpers plunder the Internet for the latest ps5 stock, hoarding until they can raise the prices further still. Greatest among these cretins are the anti-vaxxers, complete morons, idiots with IQs less than a goat. Their fools in arms are endless: flat earthers and countless moon landing deniers, the stubborn Facebook politicians and the Karen's of every shopping centre to name only a few. And with all this nonsense they won't shut up about 5G, Immigrants, muh freedoms... and far, far worse.

                    To be a man (or woman) in such times is to be one amongst almost 7 billion. It is to live in the stupidest and most irritating regime imaginable. These are the memes of these times. Forget the power of technology and science, for so much will be denied, never to be acknowledged. Forget the promise of immunity and vaccinations, for in our grim dark daily lives, there is only COVID. There is no freedom on our streets, only an eternity of mask mandates and sanitizer, and the coughing of the sick.

                    """.trimIndent()
            ).queue()
        }
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

    //Auto joining voice channel when it becomes occupied and an audio connection doesn't already exist on the server, then play the associated user's intro song
    private fun onGuildVoiceJoin(event: GuildVoiceUpdateEvent) {
        val guild = event.guild
        val audioManager = guild.audioManager
        val volumePropertyName = ConfigDto.Configurations.VOLUME.configValue
        val databaseVolumeConfig = configService.getConfigByName(volumePropertyName, event.guild.id)
        val deleteDelayConfig =
            configService.getConfigByName(ConfigDto.Configurations.DELETE_DELAY.configValue, event.guild.id)

        val defaultVolume = databaseVolumeConfig?.value?.toInt() ?: 100
        val nonBotConnectedMembers =
            event.channelJoined!!.members.stream().filter { member: Member -> !member.user.isBot }.toList()
        val audioPlayer= PlayerManager.instance.getMusicManager(guild).audioPlayer
        checkCurrentAudioManagerForNonBotMembers(audioManager)
        if (nonBotConnectedMembers.isNotEmpty() && !audioManager.isConnected) {
            audioPlayer.volume = defaultVolume
            audioManager.openAudioConnection(event.channelJoined)
        }

        val member = event.member
        val discordId = member.user.idLong
        val guildId = member.guild.idLong
        val requestingUserDto = calculateUserDto(
            guildId, discordId, Objects.requireNonNull(event.member).isOwner, userService, defaultVolume
        )

        if (audioManager.connectedChannel == event.channelJoined) {
            playUserIntro(requestingUserDto, guild, deleteDelayConfig?.value?.toInt()!!, 0L)
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
                val nonBotConnectedMembers =
                    event.channelJoined!!.members.stream().filter { member: Member -> !member.user.isBot }.toList()
                if (nonBotConnectedMembers.isNotEmpty()) {
                    PlayerManager.instance.getMusicManager(guild).audioPlayer?.volume = defaultVolume
                    am.openAudioConnection(event.channelJoined)
                    LOGGER.info("Audio connection opened.")
                }
                deleteTemporaryChannelIfEmpty(nonBotConnectedMembers.isEmpty(), event.channelLeft)
            }
        }
    }


    //Auto leaving voice channel when it becomes empty
    private fun onGuildVoiceLeave(event: GuildVoiceUpdateEvent) {
        val guild = event.guild
        val audioManager = guild.audioManager
        val nonBotConnectedMembers =
            event.channelLeft!!.members.stream().filter { member: Member -> !member.user.isBot }
                .collect(Collectors.toList())
        checkCurrentAudioManagerForNonBotMembers(audioManager)
        deleteTemporaryChannelIfEmpty(nonBotConnectedMembers.isEmpty(), event.channelLeft)
    }

    private fun deleteTemporaryChannelIfEmpty(nonBotConnectedMembersEmpty: Boolean, channelLeft: AudioChannel?) {
        //The autogenerated channels from the team command are "Team #", so this will delete them once they become empty
        if (channelLeft!!.name.matches("(?i)team\\s[0-9]+".toRegex()) && nonBotConnectedMembersEmpty) {
            channelLeft.delete().queue()
        }
    }

    override fun onStringSelectInteraction(event: StringSelectInteractionEvent) {
        menuManager.handle(event)
    }

    companion object {
        private val LOGGER: Logger = LoggerFactory.getLogger(Handler::class.java)
        private fun checkCurrentAudioManagerForNonBotMembers(audioManager: AudioManager): AudioManager {
            if (audioManager.isConnected) {
                val membersInConnectedVoiceChannel =
                    audioManager.connectedChannel!!.members.stream().filter { member: Member -> !member.user.isBot }
                        .toList()
                if (membersInConnectedVoiceChannel.isEmpty()) {
                    closeAudioPlayer(audioManager.guild, audioManager)
                }
            }
            return audioManager
        }

        private fun closeAudioPlayer(guild: Guild, audioManager: AudioManager) {
            val musicManager: GuildMusicManager = PlayerManager.instance.getMusicManager(guild)
            musicManager.scheduler.isLooping = false
            musicManager.scheduler.queue.clear()
            musicManager.audioPlayer.stopTrack()
            audioManager.closeAudioConnection()
        }
    }
}