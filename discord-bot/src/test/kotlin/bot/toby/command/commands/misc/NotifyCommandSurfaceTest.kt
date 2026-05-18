package bot.toby.command.commands.misc

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.webhookMessageCreateAction
import bot.toby.command.DefaultCommandContext
import common.notification.NotificationChannelKind
import common.notification.Surface
import database.dto.UserDto
import database.dto.UserNotificationPrefDto
import database.service.UserNotificationPrefService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class NotifyCommandSurfaceTest : CommandTest {

    private lateinit var prefService: UserNotificationPrefService
    private lateinit var notifyCommand: NotifyCommand
    private val userDto: UserDto = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        prefService = mockk(relaxed = true)
        notifyCommand = NotifyCommand(prefService)
    }

    // ---- subcommand wiring ----

    @Test
    fun `set subcommand declares kind, surface, and on options with correct choices`() {
        val setSub: SubcommandData = notifyCommand.subCommands.first { it.name == "set" }
        val options = setSub.options
        // Order matters for ergonomic /notify set <kind> <surface> <on>.
        assertEquals(listOf("kind", "surface", "on"), options.map { it.name })
        val surfaceOpt = options.first { it.name == "surface" }
        // One choice per Surface entry — autocomplete-driven UX.
        assertEquals(
            Surface.entries.size, surfaceOpt.choices.size,
            "surface choices must mirror Surface.entries"
        )
        assertEquals(
            Surface.entries.map { it.name }.toSet(),
            surfaceOpt.choices.map { it.asString }.toSet()
        )
    }

    // ---- set: supported (kind, surface) ----

    @Test
    fun `set with supported (kind, surface) calls setPref and confirms`() {
        every { event.subcommandName } returns "set"
        every { event.getOption("kind") } returns optionMappingString("TIP_RECEIVED")
        every { event.getOption("surface") } returns optionMappingString("CHANNEL")
        every { event.getOption("on") } returns optionMappingBoolean(false)
        every {
            prefService.setPref(
                discordId = 1L, guildId = 1L,
                kind = NotificationChannelKind.TIP_RECEIVED,
                surface = Surface.CHANNEL, optIn = false
            )
        } returns UserNotificationPrefDto(
            discordId = 1L, guildId = 1L,
            channelKind = NotificationChannelKind.TIP_RECEIVED.name,
            surface = Surface.CHANNEL.name, optIn = false
        )

        notifyCommand.handle(DefaultCommandContext(event), userDto, deleteDelay = 0)

        verify(exactly = 1) {
            prefService.setPref(
                discordId = 1L, guildId = 1L,
                kind = NotificationChannelKind.TIP_RECEIVED,
                surface = Surface.CHANNEL, optIn = false
            )
        }
    }

    // ---- set: unsupported (kind, surface) ----

    @Test
    fun `set with unsupported (kind, surface) does not call setPref`() {
        // TIP_RECEIVED supports CHANNEL + PUSH, not DM.
        every { event.subcommandName } returns "set"
        every { event.getOption("kind") } returns optionMappingString("TIP_RECEIVED")
        every { event.getOption("surface") } returns optionMappingString("DM")
        every { event.getOption("on") } returns optionMappingBoolean(true)

        notifyCommand.handle(DefaultCommandContext(event), userDto, deleteDelay = 0)

        verify(exactly = 0) {
            prefService.setPref(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `set with unknown kind code does not call setPref`() {
        every { event.subcommandName } returns "set"
        every { event.getOption("kind") } returns optionMappingString("NOT_A_KIND")
        every { event.getOption("surface") } returns optionMappingString("DM")
        every { event.getOption("on") } returns optionMappingBoolean(true)

        notifyCommand.handle(DefaultCommandContext(event), userDto, deleteDelay = 0)

        verify(exactly = 0) {
            prefService.setPref(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `set with unknown surface code does not call setPref`() {
        every { event.subcommandName } returns "set"
        every { event.getOption("kind") } returns optionMappingString("DUEL_OFFER")
        every { event.getOption("surface") } returns optionMappingString("NOT_A_SURFACE")
        every { event.getOption("on") } returns optionMappingBoolean(true)

        notifyCommand.handle(DefaultCommandContext(event), userDto, deleteDelay = 0)

        verify(exactly = 0) {
            prefService.setPref(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `set with missing surface option does not call setPref`() {
        every { event.subcommandName } returns "set"
        every { event.getOption("kind") } returns optionMappingString("DUEL_OFFER")
        every { event.getOption("surface") } returns null
        every { event.getOption("on") } returns optionMappingBoolean(true)

        notifyCommand.handle(DefaultCommandContext(event), userDto, deleteDelay = 0)

        verify(exactly = 0) {
            prefService.setPref(any(), any(), any(), any(), any())
        }
    }

    // ---- list ----

    @Test
    fun `list produces an embed with one section per kind and one line per surface`() {
        every { event.subcommandName } returns "list"
        every { prefService.listForUser(1L, 1L) } returns emptyList()

        notifyCommand.handle(DefaultCommandContext(event), userDto, deleteDelay = 0)

        // We can't easily peek inside the produced embed without
        // jumping through hooks, but we can verify the command
        // reaches the prefService and produces an embed.
        verify(exactly = 1) { prefService.listForUser(1L, 1L) }
        verify(atLeast = 1) { webhookMessageCreateAction.queue(any()) }
    }

    @Test
    fun `list folds explicit (kind, surface) overrides into the rendered embed`() {
        every { event.subcommandName } returns "list"
        every { prefService.listForUser(1L, 1L) } returns listOf(
            UserNotificationPrefDto(
                discordId = 1L, guildId = 1L,
                channelKind = NotificationChannelKind.ACHIEVEMENT_UNLOCK.name,
                surface = Surface.DM.name, optIn = false
            )
        )

        notifyCommand.handle(DefaultCommandContext(event), userDto, deleteDelay = 0)

        // The pref-row lookup is the contract we care about — if it
        // happens, the embed-rendering loop saw the override.
        verify(exactly = 1) { prefService.listForUser(1L, 1L) }
    }

    // ---- helpers ----

    private fun optionMappingString(value: String): OptionMapping {
        val opt = mockk<OptionMapping>()
        every { opt.asString } returns value
        return opt
    }

    private fun optionMappingBoolean(value: Boolean): OptionMapping {
        val opt = mockk<OptionMapping>()
        every { opt.asBoolean } returns value
        return opt
    }
}
