import io.mockk.*
import io.mockk.junit5.MockKExtension
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.SelfUser
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.managers.AudioManager
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction
import net.dv8tion.jda.api.utils.cache.SnowflakeCacheView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import toby.handler.VoiceEventHandler
import toby.helpers.UserDtoHelper
import toby.jpa.dto.ConfigDto
import toby.jpa.service.IConfigService
import toby.lavaplayer.PlayerManager

@ExtendWith(MockKExtension::class)
class VoiceEventHandlerTest {

    private val jda: JDA = mockk()
    private val configService: IConfigService = mockk()
    private val userDtoHelper: UserDtoHelper = mockk()

    private val handler = spyk(
        VoiceEventHandler(
            jda,
            configService,
            userDtoHelper
        )
    )

    @BeforeEach
    fun setup() {
        val selfUser = mockk<SelfUser>()
        every { jda.selfUser } returns selfUser
        every { selfUser.name } returns "TestBot"
        every { selfUser.idLong } returns 12345L
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `onReady should connect to the most populated voice channel`() {
        val guild1 = mockk<Guild>()
        val guild2 = mockk<Guild>()
        val readyEvent = mockk<ReadyEvent>()
        val voiceChannel1 = mockk<VoiceChannel>()
        val voiceChannel2 = mockk<VoiceChannel>()
        val nonBotMember1 = mockk<Member>()
        val nonBotMember2 = mockk<Member>()
        val botMember = mockk<Member>()
        val audioManager1 = mockk<AudioManager>()
        val audioManager2 = mockk<AudioManager>()
        val guildCache = mockk<SnowflakeCacheView<Guild>>()
        val commandListUpdateAction = mockk<CommandListUpdateAction>()

        // Mocking the chain
        every { readyEvent.jda } returns jda
        every { jda.updateCommands() } returns commandListUpdateAction
        every { jda.guildCache } returns guildCache
        every { commandListUpdateAction.addCommands(any<List<CommandData>>()) } returns commandListUpdateAction
        every { commandListUpdateAction.queue() } just Runs

        every { guildCache.iterator() } returns mutableListOf(guild1, guild2).iterator()

        every { guild1.voiceChannels } returns listOf(voiceChannel1)
        every { guild1.idLong } returns 1L
        every { guild1.name } returns "Guild 1"
        every { guild2.voiceChannels } returns listOf(voiceChannel2)
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

        val handler = VoiceEventHandler(
            jda = jda,
            configService = mockk(),
            userDtoHelper = mockk(),
        )

        handler.onReady(readyEvent)

        verify(exactly = 1) { audioManager1.openAudioConnection(voiceChannel1) }
        verify(exactly = 1) { audioManager2.openAudioConnection(voiceChannel2) }
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
        every { member.id } returns "1234"
        every { member.effectiveName } returns "Effective Name"
        every { member.user } returns mockk {
            every { idLong } returns 1L
        }
        every { guild.idLong } returns 1L
        every { guild.id } returns "1"
        every { guild.id } returns "1"
        every { guild.name } returns "guildName"
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
                ConfigDto.Configurations.DELETE_DELAY.configValue, "1"
            )
        } returns deleteDelayConfig

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
            every { idLong } returns 123L
            every { id } returns "1234"
            every { user } returns mockk {
                every { idLong } returns 123L
            }
        }
        every { channel.members } returns emptyList()
        every { guild.idLong } returns 1L
        every { guild.id } returns "1"
        every { guild.name } returns "guildName"
        every { channel.name } returns "team 1"
        every { audioManager.connectedChannel } returns null
        every { channel.delete().queue() } just Runs

        handler.onGuildVoiceUpdate(event)

        verify {
            channel.delete().queue()
        }
    }

    @Test
    fun `onGuildVoiceMove should rejoin previous channel when bot is moved`() {
        val guild = mockk<Guild>()
        val event = mockk<GuildVoiceUpdateEvent>()
        val audioManager = mockk<AudioManager>()
        val member = mockk<Member>()
        val previousChannel = mockk<VoiceChannel>()
        val newChannel = mockk<AudioChannelUnion>(relaxed = true)

        // Mocking event and guild behavior
        every { event.guild } returns guild
        every { event.member } returns member
        every { guild.audioManager } returns audioManager
        every { audioManager.guild } returns guild
        every { guild.idLong } returns 1L
        every { guild.id } returns "1"
        every { guild.name } returns "guildName"
        every { member.user.idLong } returns 12345L  // Simulate the bot's ID
        every { member.effectiveName } returns "effectiveName" // Simulate the bot's ID
        every { member.idLong } returns 1234L
        every { member.id } returns "1234"
        every { event.jda.selfUser.idLong } returns 12345L  // Simulate the bot's self ID
        every { event.channelJoined } returns newChannel
        every { event.channelLeft } returns mockk()

        // Simulate previous channel in lastConnectedChannel
        VoiceEventHandler.lastConnectedChannel[guild.idLong] = previousChannel

        // Mock the audioManager behavior
        every { audioManager.openAudioConnection(any()) } just Runs
        every { previousChannel.name } returns "PreviousChannel"

        handler.onGuildVoiceUpdate(event)

        // Verifying that the bot tries to rejoin the previous channel
        verify(exactly = 1) { audioManager.openAudioConnection(previousChannel) }
    }

    @Test
    fun `onGuildVoiceMove should log warning when bot is moved and no previous channel exists`() {
        val guild = mockk<Guild>()
        val event = mockk<GuildVoiceUpdateEvent>()
        val audioManager = mockk<AudioManager>()
        val member = mockk<Member>()

        // Mocking event and guild behavior
        every { event.guild } returns guild
        every { event.member } returns member
        every { guild.audioManager } returns audioManager
        every { guild.name } returns "guildName"
        every { audioManager.guild } returns guild
        every { member.user.idLong } returns 12345L  // Simulate the bot's ID
        every { member.idLong } returns 12345L  // Simulate the bot's ID
        every { member.id } returns "12345"  // Simulate the bot's ID
        every { event.jda.selfUser.idLong } returns 12345L  // Simulate the bot's self ID
        every { guild.idLong } returns 1L
        every { guild.id } returns "1"
        every { event.channelJoined } returns mockk {
            every { asVoiceChannel() } returns mockk(relaxed = true)
        }
        every { event.channelLeft } returns mockk {
            every { asVoiceChannel() } returns mockk(relaxed = true)
        }

        every { member.effectiveName } returns "BotName"


        // Simulate no previous channel in lastConnectedChannel
        VoiceEventHandler.lastConnectedChannel.remove(guild.idLong)

        handler.onGuildVoiceUpdate(event)

        // Verifying that a warning log is printed
        verify(exactly = 0) { audioManager.closeAudioConnection() }
        verify(exactly = 0) { audioManager.openAudioConnection(any()) }
    }


    @Test
    fun `onGuildVoiceMove should check to close connection and join user`() {
        val guild = mockk<Guild>()
        val event = mockk<GuildVoiceUpdateEvent>()
        val audioManager = mockk<AudioManager>(relaxed = true)
        val member = mockk<Member>()
        val newChannel = mockk<AudioChannelUnion>(relaxed = true)

        every { event.guild } returns guild
        every { event.member } returns member
        every { event.channelJoined } returns newChannel
        every { event.channelLeft } returns mockk()
        every { guild.audioManager } returns audioManager
        every { audioManager.guild } returns guild
        every { member.user.idLong } returns 54321L  // Not bot's ID
        every { member.idLong } returns 54321L  // Not bot's ID
        every { member.id } returns "54321"  // Not bot's ID
        every { guild.idLong } returns 1L
        every { guild.id } returns "1"
        every { guild.name } returns "guildName"
        every { member.effectiveName } returns "Some User"
        every { member.user.isBot } returns false
        every { newChannel.members } returns listOf(member)

        // Mock configuration service
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.VOLUME.configValue,
                "1"
            )
        } returns ConfigDto().apply { value = "50" }

        handler.onGuildVoiceUpdate(event)

        // Verify that the bot checked to close connection and joined the new channel
        verify(exactly = 1) { audioManager.closeAudioConnection() }
        verify(exactly = 1) { audioManager.openAudioConnection(newChannel) }
    }
}
