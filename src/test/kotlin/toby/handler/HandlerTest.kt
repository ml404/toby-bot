import io.mockk.*
import io.mockk.junit5.MockKExtension
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.*
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.managers.AudioManager
import net.dv8tion.jda.api.utils.cache.SnowflakeCacheView
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import toby.emote.Emotes
import toby.handler.Handler
import toby.jpa.dto.ConfigDto
import toby.jpa.service.*
import toby.lavaplayer.PlayerManager
import toby.managers.ButtonManager
import toby.managers.CommandManager

@ExtendWith(MockKExtension::class)
class HandlerTest {

    private val configService: IConfigService = mockk()
    private val userService: IUserService = mockk()
    private val brotherService: IBrotherService = mockk()
    private val musicFileService: IMusicFileService = mockk()
    private val excuseService: IExcuseService = mockk()
    private val commandManager: CommandManager = mockk()
    private val buttonManager: ButtonManager = mockk()
    private val handler = spyk(Handler(
        configService,
        brotherService,
        userService,
        musicFileService,
        excuseService,
        commandManager,
        buttonManager
    ))

    @Test
    fun `onReady should connect to the most populated voice channel`() {
        val jda = mockk<JDA>()
        val selfUser = mockk<SelfUser>()
        val guild1 = mockk<Guild>()
        val guild2 = mockk<Guild>()
        val readyEvent = mockk<ReadyEvent>()
        val voiceChannelUnion1 = mockk<AudioChannelUnion>()
        val voiceChannel1 = mockk<VoiceChannel>()
        val voiceChannelUnion2 = mockk<AudioChannelUnion>()
        val voiceChannel2 = mockk<VoiceChannel>()
        val nonBotMember1 = mockk<Member>()
        val nonBotMember2 = mockk<Member>()
        val botMember = mockk<Member>()
        val audioManager1 = mockk<AudioManager>()
        val audioManager2 = mockk<AudioManager>()
        val guildCache = mockk<SnowflakeCacheView<Guild>>()

        every { readyEvent.jda } returns jda
        every { jda.selfUser } returns selfUser
        every { selfUser.name } returns "TestBot"
        every { jda.guildCache } returns guildCache
        every { guildCache.iterator() } returns mutableListOf(guild1, guild2).iterator()
        every { voiceChannelUnion1.asVoiceChannel() } returns voiceChannel1
        every { voiceChannelUnion2.asVoiceChannel() } returns voiceChannel2

        every { guild1.voiceChannels } returns listOf(voiceChannelUnion1.asVoiceChannel())
        every { guild1.idLong } returns 1L
        every { guild1.name } returns "Guild 1"
        every { guild2.voiceChannels } returns listOf(voiceChannelUnion2.asVoiceChannel())
        every { guild2.idLong } returns 2L
        every { guild2.name } returns "Guild 2"

        every { voiceChannel1.members } returns listOf(nonBotMember1, botMember)
        every { voiceChannel1.guild } returns guild1
        every { voiceChannel2.members } returns listOf(nonBotMember2)
        every { voiceChannel2.guild } returns guild2

        every { nonBotMember1.user.isBot } returns false
        every { nonBotMember2.user.isBot } returns false
        every { botMember.user.isBot } returns true

        every { guild1.audioManager } returns audioManager1
        every { guild2.audioManager } returns audioManager2

        every { audioManager1.isConnected } returns false
        every { audioManager2.isConnected } returns false
        every { audioManager1.openAudioConnection(any()) } just Runs
        every { audioManager2.openAudioConnection(any()) } just Runs

        handler.onReady(readyEvent)

        verify(exactly = 1) { audioManager1.openAudioConnection(voiceChannel1) }
        verify(exactly = 1) { audioManager2.openAudioConnection(voiceChannel2) }
    }

    @Test
    fun `onMessageReceived should respond correctly to toby message`() {
        val event = mockk<MessageReceivedEvent>()
        val message = mockk<Message>()
        val author = mockk<User>()
        val channel = mockk<MessageChannelUnion>()
        val guild = mockk<Guild>()
        val member = mockk<Member>()

        every { event.message } returns message
        every { event.author } returns author
        every { event.channel } returns channel
        every { event.guild } returns guild
        every { event.member } returns member
        every { author.isBot } returns false
        every { event.isWebhookMessage } returns false
        every { message.contentRaw } returns "toby"
        every { member.effectiveName } returns "Matt"

        val tobyEmote = mockk<RichCustomEmoji> {
            every { asMention } returns "<:toby:123456789>"
        }
        every { guild.jda.getEmojiById(Emotes.TOBY) } returns tobyEmote

        every { channel.sendMessageFormat(any(), any(), any()).queue() } returns mockk()
        every { message.addReaction(tobyEmote).queue() } returns mockk()

        handler.onMessageReceived(event)

        verify {
            channel.sendMessageFormat(any(), any(), any())
            message.addReaction(tobyEmote)
        }
    }

    @Test
    fun `onMessageReceived should respond correctly to sigh message`() {
        val event = mockk<MessageReceivedEvent>()
        val message = mockk<Message>()
        val author = mockk<User>()
        val channel = mockk<MessageChannelUnion>()
        val guild = mockk<Guild>()
        val member = mockk<Member>()

        every { event.message } returns message
        every { event.author } returns author
        every { event.channel } returns channel
        every { event.guild } returns guild
        every { event.member } returns member
        every { author.isBot } returns false
        every { event.isWebhookMessage } returns false
        every { message.contentRaw } returns "sigh"
        every { member.effectiveName } returns "Matt"

        val jessEmote = mockk<RichCustomEmoji> {
            every { asMention } returns "<:jess:987654321>"
        }
        every { guild.jda.getEmojiById(Emotes.JESS) } returns jessEmote

        every { channel.sendMessageFormat("Hey %s, what's up champ?", "Matt", jessEmote).queue() } returns mockk()

        handler.onMessageReceived(event)

        verify {
            channel.sendMessageFormat("Hey %s, what's up champ?", "Matt", any())
        }
    }

    @Test
    fun `onMessageReceived should respond correctly to yeah message`() {
        val event = mockk<MessageReceivedEvent>()
        val message = mockk<Message>()
        val author = mockk<User>()
        val channel = mockk<MessageChannelUnion>()
        val guild = mockk<Guild>()
        val member = mockk<Member>()

        every { event.message } returns message
        every { event.author } returns author
        every { event.channel } returns channel
        every { event.guild } returns guild
        every { event.member } returns member
        every { author.isBot } returns false
        every { event.isWebhookMessage } returns false
        every { message.contentRaw } returns "yeah"

        every { channel.sendMessage("YEAH????").queue() } returns mockk()

        handler.onMessageReceived(event)

        verify {
            channel.sendMessage("YEAH????")
        }
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
        every { guild.audioManager } returns audioManager
        every { event.member } returns member
        every { event.channelJoined } returns channel
        every { event.channelLeft } returns null
        every { channel.members } returns listOf(nonBotMember)
        every { channel.asVoiceChannel() } returns mockk(relaxed = true)
        every { nonBotMember.user.isBot } returns false
        every { member.guild } returns guild
        every { member.isOwner } returns false
        every { member.idLong } returns 1L
        every { member.effectiveName } returns "Effective Name"
        every { guild.idLong } returns 1L
        every { guild.id } returns "1"
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
                ConfigDto.Configurations.DELETE_DELAY.configValue,
                "1"
            )
        } returns deleteDelayConfig
        every { userService.getUserById(1L, 1L) } returns mockk()

        handler.onGuildVoiceUpdate(event)

        verify {
            audioManager.openAudioConnection(channel)
            PlayerManager.instance
            audioPlayerManager.getMusicManager(guild).audioPlayer.volume = any()
        }
    }

    @Test
    fun `onGuildVoiceUpdate should handle voice leave`() {
        val guild = mockk<Guild>()
        val event = mockk<GuildVoiceUpdateEvent>()
        val audioManager = mockk<AudioManager>()
        val channel = mockk<AudioChannelUnion>()

        every { event.guild } returns guild
        every { guild.audioManager } returns audioManager
        every { event.channelJoined } returns null
        every { event.channelLeft } returns channel
        every { event.member } returns mockk {
            every { effectiveName } returns "Effective Name"
        }
        every { channel.members } returns emptyList()
        every { guild.idLong } returns 1L
        every { guild.id } returns "1"
        every { channel.name } returns "team 1"
        every { audioManager.connectedChannel } returns null
        every { channel.delete().queue() } just Runs

        handler.onGuildVoiceUpdate(event)

        verify {
            channel.delete().queue()
        }
    }
}
