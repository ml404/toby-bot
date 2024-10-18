package bot.toby.command.commands.misc

import bot.toby.command.CommandContextImpl
import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import database.dto.ConfigDto
import database.dto.MusicDto
import database.service.ConfigService
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HelloThereCommandTest : CommandTest {
    lateinit var command: HelloThereCommand

    private val configService = mockk<ConfigService>()

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        command = HelloThereCommand(configService)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearMocks(configService)
    }

    @Test
    fun testWithOptionAfterEp3() {
        val messageOption = mockk<OptionMapping>()
        every { messageOption.asString } returns "2005/05/20"
        every { event.getOption("date") } returns messageOption

        val requestingUserDto = userDto // You can set the user as needed

        every { event.options } returns listOf(messageOption)
        every { event.getOption(any<String>()) } returns messageOption
        every { configService.getConfigByName("DATEFORMAT", "1") } returns ConfigDto("DATEFORMAT", "yyyy/MM/dd", "1")

        command.handle(CommandContextImpl(event), requestingUserDto, 0)

        verify { event.hook.sendMessage("General Kenobi.") }
    }

    @Test
    fun testWithOptionBeforeEp3() {
        val messageOption = mockk<OptionMapping>()
        every { messageOption.asString } returns "2005/05/18"
        every { event.getOption("date") } returns messageOption

        val requestingUserDto = userDto // You can set the user as needed

        every { event.options } returns listOf(messageOption)
        every { event.getOption(any<String>()) } returns messageOption
        every { configService.getConfigByName("DATEFORMAT", "1") } returns ConfigDto("DATEFORMAT", "yyyy/MM/dd", "1")

        command.handle(CommandContextImpl(event), requestingUserDto, 0)

        verify { event.hook.sendMessage("Hello.") }
    }

    @Test
    fun testWithNoOption() {
        every { event.getOption("date") } returns null
        every { configService.getConfigByName("DATEFORMAT", "1") } returns ConfigDto("DATEFORMAT", "yyyy/MM/dd", "1")

        val requestingUserDto = userDto // You can set the user as needed

        command.handle(CommandContextImpl(event), requestingUserDto, 0)

        verify { event.hook.sendMessage("I have a bad understanding of time, let me know what the date is so I can greet you appropriately") }
    }

    @Test
    fun testWithInvalidDateFormat() {
        val messageOption = mockk<OptionMapping>()
        every { messageOption.asString } returns "19/05/2005"
        every { event.getOption("date") } returns messageOption

        val requestingUserDto = userDto // You can set the user as needed

        every { event.options } returns listOf(messageOption)
        every { event.getOption(any<String>()) } returns messageOption
        every { configService.getConfigByName("DATEFORMAT", "1") } returns ConfigDto("DATEFORMAT", "yyyy/MM/dd", "1")

        command.handle(CommandContextImpl(event), requestingUserDto, 0)

        verify {
            event.hook.sendMessage(
                "I don't recognise the format of the date you gave me, please use this format yyyy/MM/dd"
            )
        }
    }

    companion object {
        private val userDto: database.dto.UserDto
            get() = database.dto.UserDto(
                1L, 1L,
                superUser = true,
                musicPermission = true,
                digPermission = true,
                memePermission = true,
                socialCredit = 0L,
                musicDtos = emptyList<MusicDto>().toMutableList()
            )
    }
}
