package bot.toby.managers

import bot.toby.helpers.UserDtoHelper
import core.command.Command
import database.dto.guild.ConfigDto
import database.dto.user.UserDto
import database.service.guild.ConfigService
import database.service.social.SocialCreditAwardService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import io.mockk.verifyOrder
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Guard the ordering change introduced for Issue 4: the social-credit award
 * is now fired **before** command dispatch so a thrown handler still earns
 * credit and so every user-initiated action shares one hook point.
 */
class DefaultCommandManagerAwardTest {

    private lateinit var configService: ConfigService
    private lateinit var userDtoHelper: UserDtoHelper
    private lateinit var awardService: SocialCreditAwardService
    private lateinit var manager: DefaultCommandManager

    private val guildId = 7L
    private val discordId = 100L

    @BeforeEach
    fun setup() {
        configService = mockk(relaxed = true) {
            every { getConfigByName(any(), any()) } returns ConfigDto("x", "5")
        }
        userDtoHelper = mockk(relaxed = true) {
            every { calculateUserDto(discordId, guildId, any()) } returns
                UserDto(discordId = discordId, guildId = guildId).apply { socialCredit = 0L }
        }
        awardService = mockk(relaxed = true)
        manager = DefaultCommandManager(configService, userDtoHelper, awardService, emptyList())
    }

    private fun eventFor(commandName: String): SlashCommandInteractionEvent {
        val guild: Guild = mockk(relaxed = true) {
            every { id } returns guildId.toString()
            every { idLong } returns guildId
        }
        val member: Member = mockk(relaxed = true) {
            every { isOwner } returns false
        }
        val user: User = mockk(relaxed = true) {
            every { idLong } returns discordId
        }
        val channel: MessageChannelUnion = mockk(relaxed = true)
        val hook: InteractionHook = mockk(relaxed = true)
        val event: SlashCommandInteractionEvent = mockk(relaxed = true)
        every { event.guild } returns guild
        every { event.member } returns member
        every { event.user } returns user
        every { event.channel } returns channel
        every { event.hook } returns hook
        every { event.name } returns commandName
        return event
    }

    @Test
    fun `award fires before command handle`() {
        val command = mockk<Command>(relaxed = true) {
            every { name } returns "ping"
            every { slashCommand } returns mockk(relaxed = true)
            every { subCommands } returns emptyList()
            every { optionData } returns emptyList()
            every { handle(any(), any(), any()) } just Runs
        }
        val spy = spyk(manager)
        every { spy.getCommand("ping") } returns command

        spy.handle(eventFor("ping"))

        verifyOrder {
            awardService.award(discordId, guildId, any(), "command:ping", any(), any())
            command.handle(any(), any(), any())
        }
    }

    @Test
    fun `award fires even when the command handler throws`() {
        val command = mockk<Command>(relaxed = true) {
            every { name } returns "boom"
            every { slashCommand } returns mockk(relaxed = true)
            every { subCommands } returns emptyList()
            every { optionData } returns emptyList()
            every { handle(any(), any(), any()) } throws RuntimeException("boom")
        }
        val spy = spyk(manager)
        every { spy.getCommand("boom") } returns command

        runCatching { spy.handle(eventFor("boom")) }

        verify(exactly = 1) {
            awardService.award(discordId, guildId, any(), "command:boom", any(), any())
        }
    }

    @Test
    fun `defer fires before pre-dispatch DB lookups`() {
        // Regression guard: pre-dispatch DB calls used to run before any
        // ack, eating the 3-second Discord window when the DB was slow.
        // The manager now defers first based on Command.defersReply.
        val command = mockk<Command>(relaxed = true) {
            every { name } returns "ping"
            every { defersReply } returns true
            every { ephemeral } returns false
            every { slashCommand } returns mockk(relaxed = true)
            every { subCommands } returns emptyList()
            every { optionData } returns emptyList()
            every { handle(any(), any(), any()) } just Runs
        }
        val spy = spyk(manager)
        every { spy.getCommand("ping") } returns command
        val event = eventFor("ping")

        spy.handle(event)

        verifyOrder {
            event.deferReply(false)
            configService.getConfigByName(any(), any())
            userDtoHelper.calculateUserDto(any(), any(), any())
            command.handle(any(), any(), any())
        }
    }

    @Test
    fun `defer is ephemeral when the command opts in`() {
        val command = mockk<Command>(relaxed = true) {
            every { name } returns "secret"
            every { defersReply } returns true
            every { ephemeral } returns true
            every { slashCommand } returns mockk(relaxed = true)
            every { subCommands } returns emptyList()
            every { optionData } returns emptyList()
            every { handle(any(), any(), any()) } just Runs
        }
        val spy = spyk(manager)
        every { spy.getCommand("secret") } returns command
        val event = eventFor("secret")

        spy.handle(event)

        verify(exactly = 1) { event.deferReply(true) }
    }

    @Test
    fun `no auto-defer when command opts out (modal commands)`() {
        // Commands that respond directly (e.g. event.replyModal) must opt
        // out — a manager-side deferReply would block their reply.
        val command = mockk<Command>(relaxed = true) {
            every { name } returns "tip"
            every { defersReply } returns false
            every { slashCommand } returns mockk(relaxed = true)
            every { subCommands } returns emptyList()
            every { optionData } returns emptyList()
            every { handle(any(), any(), any()) } just Runs
        }
        val spy = spyk(manager)
        every { spy.getCommand("tip") } returns command
        val event = eventFor("tip")

        spy.handle(event)

        verify(exactly = 0) { event.deferReply(any<Boolean>()) }
    }
}
