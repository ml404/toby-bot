package toby.command.commands.misc

import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import toby.command.CommandContext
import toby.command.CommandTest
import toby.jpa.dto.ConfigDto
import toby.jpa.dto.UserDto
import toby.jpa.service.IConfigService

class HelloThereCommandTest : CommandTest {
    lateinit var command: HelloThereCommand

    @Mock
    lateinit var configService: IConfigService

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        configService = Mockito.mock(IConfigService::class.java)
        command = HelloThereCommand(configService)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
    }

    @Test
    fun testWithOptionAfterEp3() {
        val messageOption = Mockito.mock(OptionMapping::class.java)
        `when`(messageOption.asString).thenReturn("2005/05/20")
        `when`<OptionMapping>(CommandTest.event.getOption("date")).thenReturn(messageOption)

        val requestingUserDto = userDto // You can set the user as needed

        // Mock the event to return the MESSAGE option
        `when`<List<OptionMapping>>(CommandTest.event.options).thenReturn(listOf(messageOption))
        `when`<OptionMapping>(CommandTest.event.getOption(ArgumentMatchers.anyString()))
            .thenReturn(messageOption)
        `when`(configService.getConfigByName("DATEFORMAT", "1"))
            .thenReturn(ConfigDto("DATEFORMAT", "yyyy/MM/dd", "1"))

        command.handle(CommandContext(CommandTest.event), requestingUserDto, 0)

        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessage("General Kenobi.")
    }


    @Test
    fun testWithOptionBeforeEp3() {
        val messageOption = Mockito.mock(OptionMapping::class.java)
        `when`(messageOption.asString).thenReturn("2005/05/18")
        `when`<OptionMapping>(CommandTest.event.getOption("date")).thenReturn(messageOption)

        val requestingUserDto = userDto // You can set the user as needed

        // Mock the event to return the MESSAGE option
        `when`<List<OptionMapping>>(CommandTest.event.options).thenReturn(listOf(messageOption))
        `when`<OptionMapping>(CommandTest.event.getOption(ArgumentMatchers.anyString()))
            .thenReturn(messageOption)
        `when`(configService.getConfigByName("DATEFORMAT", "1"))
            .thenReturn(ConfigDto("DATEFORMAT", "yyyy/MM/dd", "1"))

        command.handle(CommandContext(CommandTest.event), requestingUserDto, 0)

        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessage("Hello.")
    }

    @Test
    fun testWithNoOption() {
        val messageOption = Mockito.mock(OptionMapping::class.java)

        val requestingUserDto = userDto // You can set the user as needed

        // Mock the event to return the MESSAGE option
        `when`<OptionMapping>(CommandTest.event.getOption(ArgumentMatchers.anyString())).thenReturn(messageOption)
        `when`(configService.getConfigByName("DATEFORMAT", "1"))
            .thenReturn(ConfigDto("DATEFORMAT", "yyyy/MM/dd", "1"))

        command.handle(CommandContext(CommandTest.event), requestingUserDto, 0)

        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessage("I have a bad understanding of time, let me know what the date is so I can greet you appropriately")
    }

    @Test
    fun testWithInvalidDateFormat() {
        val messageOption = Mockito.mock(OptionMapping::class.java)
        `when`(messageOption.asString).thenReturn("19/05/2005")
        `when`<OptionMapping>(CommandTest.event.getOption("date")).thenReturn(messageOption)

        val requestingUserDto = userDto // You can set the user as needed

        // Mock the event to return the MESSAGE option
        `when`<List<OptionMapping>>(CommandTest.event.options).thenReturn(listOf(messageOption))
        `when`<OptionMapping>(CommandTest.event.getOption(ArgumentMatchers.anyString()))
            .thenReturn(messageOption)

        `when`(configService.getConfigByName("DATEFORMAT", "1"))
            .thenReturn(ConfigDto("DATEFORMAT", "yyyy/MM/dd", "1"))

        command.handle(CommandContext(CommandTest.event), requestingUserDto, 0)

        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            "I don't recognise the format of the date you gave me, please use this format %s",
            "yyyy/MM/dd"
        )
    }

    companion object {
        private val userDto: UserDto
            get() = UserDto(1L, 1L,
                superUser = true,
                musicPermission = true,
                digPermission = true,
                memePermission = true,
                socialCredit = 0L,
                musicDto = null
            )
    }
}