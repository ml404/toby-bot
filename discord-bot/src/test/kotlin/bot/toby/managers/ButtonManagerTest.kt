package bot.toby.managers

import bot.Application
import bot.configuration.*
import bot.toby.button.buttons.*
import bot.toby.helpers.*
import bot.toby.lavaplayer.GuildMusicManager
import bot.toby.lavaplayer.PlayerManager
import bot.toby.lavaplayer.TrackScheduler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import common.configuration.TestCachingConfig
import core.button.Button
import database.configuration.TestDatabaseConfig
import database.dto.ConfigDto
import database.service.*
import io.mockk.*
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.util.concurrent.LinkedBlockingQueue

@SpringBootTest(
    classes = [
        Application::class,
        TestAppConfig::class,
        TestBotConfig::class,
        TestCachingConfig::class,
        TestDatabaseConfig::class,
        TestManagerConfig::class
    ]
)
@ActiveProfiles("test")
class ButtonManagerTest {

    lateinit var configService: ConfigService
    private lateinit var buttonManager: DefaultButtonManager
    private lateinit var userDtoHelper: UserDtoHelper

    @Autowired
    lateinit var buttons: List<Button>

    @BeforeEach
    fun openMocks() {
        configService = mockk()
        userDtoHelper = mockk()
        buttonManager = DefaultButtonManager(configService, userDtoHelper, buttons)
        mockkStatic(PlayerManager::class)
        mockkObject(MusicPlayerHelper)

        every { userDtoHelper.calculateUserDto(1, 1, true) } returns mockk(relaxed = true)
    }

    @AfterEach
    @Throws(Exception::class)
    fun releaseMocks() {
        unmockkAll()
    }

    @Test
    fun testButtonManagerFindsAllButtons() {
        val availableButtons = listOf(
            InitiativeClearButton::class.java,
            InitiativeNextButton::class.java,
            InitiativePreviousButton::class.java,
            PausePlayButton::class.java,
            ResendLastRequestButton::class.java,
            RollButton::class.java,
            StopButton::class.java,
        )

        assertTrue(availableButtons.containsAll(buttonManager.buttons.map { it.javaClass }.toList()))
        assertEquals(7, buttonManager.buttons.size)
    }

    @Test
    fun `test handle ButtonInteractionEvent with stop`() {
        val mockGuild = mockk<Guild> {
            every { idLong } returns 1L
            every { id } returns "1"
            every { audioManager } returns mockk(relaxed = true)
            every { name } returns "guildName"
        }
        val mockChannel = mockk<MessageChannelUnion> {
            every { sendTyping().queue() } just Runs
        }

        val mockHook = mockk<InteractionHook> {
            every { deleteOriginal() } returns mockk {
                every { queue() } just Runs
            }
            every { sendMessageEmbeds(any<MessageEmbed>()) } returns mockk {
                every { queue(any()) } just Runs
            }
        }
        val event = mockk<ButtonInteractionEvent> {
            every { guild } returns mockGuild
            every { channel } returns mockChannel
            every { componentId } returns "stop"
            every { hook } returns mockHook
            every { user } returns mockk {
                every { idLong } returns 1L
            }
            every { member } returns mockk {
                every { isOwner } returns true
                every { effectiveName } returns "effectiveName"
                every { idLong } returns 123L
                every { id } returns "123"
                every { user } returns mockk {
                    every { effectiveName } returns "effectiveName"
                    every { idLong } returns 123L
                }
            }
        }

        val mockScheduler = mockk<TrackScheduler> {
            every { stopTrack(any()) } returns true
            every { queue } returns LinkedBlockingQueue()
            every { isLooping = any() } just Runs
        }

        val musicManager = mockk<GuildMusicManager> {
            every { scheduler } returns mockScheduler
        }

        mockkObject(PlayerManager) {
            every { PlayerManager.instance.getMusicManager(mockGuild) } returns musicManager
        }

        every { configService.getConfigByName(any(), any()) } returns ConfigDto("test", "1")

        buttonManager.handle(event)

        verify { mockChannel.sendTyping().queue() }
        verify { MusicPlayerHelper.stopSong(any(), any(), any(), any()) }
    }

    @Test
    fun `test handle ButtonInteractionEvent with pause_play`() {
        val mockGuild = mockk<Guild> {
            every { idLong } returns 1L
            every { id } returns "1"
            every { audioManager } returns mockk(relaxed = true)
        }
        val mockChannel = mockk<MessageChannelUnion> {
            every { sendTyping().queue() } just Runs
        }

        val mockHook = mockk<InteractionHook> {
            every { deleteOriginal() } returns mockk {
                every { queue() } just Runs
            }
            every { sendMessageEmbeds(any<MessageEmbed>()) } returns mockk {
                every { queue(any()) } just Runs
            }
        }
        val event = mockk<ButtonInteractionEvent> {
            every { guild } returns mockGuild
            every { channel } returns mockChannel
            every { componentId } returns "pause/play"
            every { hook } returns mockHook
            every { user } returns mockk {
                every { idLong } returns 1L
            }
            every { member } returns mockk {
                every { isOwner } returns true
            }
        }

        val mockAudioPlayerManager = mockk<AudioPlayerManager>()
        every { mockAudioPlayerManager.registerSourceManager(any()) } just Runs

        val playerManager = PlayerManager(mockAudioPlayerManager)

        val mockScheduler = mockk<TrackScheduler> {
            every { stopTrack(any()) } returns true
            every { queue } returns LinkedBlockingQueue()
            every { isLooping = any() } just Runs
        }


        val musicManager = mockk<GuildMusicManager> {
            every { scheduler } returns mockScheduler
            every { audioPlayer } returns mockk {
                every { playingTrack } returns mockk(relaxed = true) {
                    every { info } returns AudioTrackInfo("Title", "Author", 1000L, "identifier", true, "uri")
                }
            }
        }
        mockkObject(PlayerManager) {
            every { PlayerManager.instance } returns playerManager
        }

        // Mock the MusicPlayerHelper
        mockkObject(MusicPlayerHelper)
        every { MusicPlayerHelper.changePauseStatusOnTrack(any(), any(), any()) } just Runs

        every { playerManager.getMusicManager(mockGuild) } returns musicManager
        every { configService.getConfigByName(any(), any()) } returns ConfigDto("test", "1")

        buttonManager.handle(event)

        verify { mockChannel.sendTyping().queue() }
        verify { MusicPlayerHelper.changePauseStatusOnTrack(any(), any(), any()) }
    }
}