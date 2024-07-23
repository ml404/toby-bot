package toby.button

import io.mockk.*
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.components.buttons.ButtonInteraction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import toby.helpers.MusicPlayerHelper
import toby.jpa.service.*
import toby.lavaplayer.PlayerManager
import toby.managers.ButtonManager
import toby.managers.CommandManager

interface ButtonTest {

    @BeforeEach
    fun setup() {
        configService = mockk()
        brotherService = mockk()
        userService = mockk()
        musicFileService = mockk()
        excuseService = mockk()
        commandManager = CommandManager(configService, brotherService, userService, musicFileService, excuseService)
        buttonManager = ButtonManager(configService, userService, commandManager)
        mockkStatic(PlayerManager::class)
        mockkObject(MusicPlayerHelper)

        mockGuild = mockk<Guild> {
            every { idLong } returns 1L
            every { id } returns "1"
            every { audioManager } returns mockk(relaxed = true)
        }
        mockChannel = mockk<MessageChannelUnion> {
            every { sendTyping().queue() } just Runs
        }

        mockHook = mockk<InteractionHook> {
            every { deleteOriginal() } returns mockk {
                every { queue() } just Runs
            }
            every { sendMessageEmbeds(any<MessageEmbed>()) } returns mockk {
                every { queue(any()) } just Runs
            }
        }

        mockInteraction = mockk {
            every { hook } returns mockHook
            every { message } returns mockk {
                every { delete().queue() } just Runs
            }
        }

        event = mockk<ButtonInteractionEvent>(relaxed = true) {
            every { guild } returns mockGuild
            every { channel } returns mockChannel
            every { componentId } returns "stop"
            every { hook } returns mockHook
            every { user } returns mockk {
                every { idLong } returns 1L
            }
            every { member } returns mockk {
                every { isOwner } returns true
            }
            every { interaction } returns mockInteraction
            every { component } returns mockk {
                every { message } returns mockk {
                    every { delete().queue() } just Runs
                }
            }
        }
    }

    @AfterEach
    @Throws(Exception::class)
    fun tearDown() {
        unmockkAll()
    }

    companion object {
        lateinit var event: ButtonInteractionEvent
        lateinit var mockGuild: Guild
        lateinit var mockChannel: MessageChannelUnion
        lateinit var mockHook: InteractionHook
        lateinit var mockInteraction: ButtonInteraction
        lateinit var configService: IConfigService
        lateinit var brotherService: IBrotherService
        lateinit var userService: IUserService
        lateinit var musicFileService: IMusicFileService
        lateinit var excuseService: IExcuseService
        lateinit var commandManager: CommandManager
        lateinit var buttonManager: ButtonManager
    }
}