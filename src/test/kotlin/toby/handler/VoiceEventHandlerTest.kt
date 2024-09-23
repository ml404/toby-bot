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
        every { member.effectiveName } returns "Effective Name"
        every { member.user } returns mockk {
            every { idLong } returns 1L
        }
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
