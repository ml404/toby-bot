package bot.toby.managers

import bot.toby.helpers.UserDtoHelper
import core.command.Command
import database.dto.ConfigDto
import database.dto.UserDto
import database.service.ConfigService
import database.service.SocialCreditAwardService
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
            awardService.award(discordId, guildId, any(), "command:ping", any(), any(), any())
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
            awardService.award(discordId, guildId, any(), "command:boom", any(), any(), any())
        }
    }
}
