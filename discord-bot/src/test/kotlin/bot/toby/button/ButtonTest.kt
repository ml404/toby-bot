package bot.toby.button

import bot.toby.helpers.*
import bot.toby.lavaplayer.PlayerManager
import bot.toby.managers.ButtonManager
import database.service.*
import io.mockk.*
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.components.buttons.ButtonInteraction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

interface ButtonTest {

    @BeforeEach
    fun setup() {
        configService = mockk()
        brotherService = mockk()
        userService = mockk()
        musicFileService = mockk()
        excuseService = mockk()
        httpHelper = mockk()
        userDtoHelper = mockk()
        introHelper = mockk()
        dndHelper = mockk()
        mockkStatic(PlayerManager::class)
        mockkObject(MusicPlayerHelper)

        mockGuild = mockk<Guild> {
            every { idLong } returns 1L
            every { id } returns "1"
            every { audioManager } returns mockk(relaxed = true)
            every { name } returns "guildName"
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
                every { id } returns "1"
            }
            every { member } returns mockk {
                every { isOwner } returns true
                every { effectiveName } returns "effectiveName"
                every { user } returns mockk {
                    every {effectiveName} returns "effectiveName"
                    every {idLong} returns 123L
                    every {id} returns "123"
                }
                every { idLong } returns 1234L
                every { id } returns "1234"

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
        lateinit var buttonManager: ButtonManager
        lateinit var httpHelper: HttpHelper
        lateinit var introHelper: IntroHelper
        lateinit var userDtoHelper: UserDtoHelper
        lateinit var dndHelper: DnDHelper
    }
}