package bot.toby.command.commands.economy

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.CommandTest.Companion.user
import bot.toby.command.DefaultCommandContext
import bot.toby.helpers.UserDtoHelper
import database.dto.UserDto
import database.service.TipService
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class TipCommandTest : CommandTest {
    private lateinit var tipService: TipService
    private lateinit var userDtoHelper: UserDtoHelper
    private lateinit var command: TipCommand

    private val senderId = 1L
    private val recipientId = 2L
    private val guildId = 42L

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        tipService = mockk(relaxed = true)
        userDtoHelper = mockk(relaxed = true)
        command = TipCommand(tipService, userDtoHelper)
        every { guild.idLong } returns guildId
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearAllMocks()
    }

    private fun userOpt(target: User): OptionMapping {
        val o = mockk<OptionMapping>(relaxed = true)
        every { o.asUser } returns target
        return o
    }

    private fun intOpt(value: Long): OptionMapping {
        val o = mockk<OptionMapping>(relaxed = true)
        every { o.asLong } returns value
        return o
    }

    private fun strOpt(value: String): OptionMapping {
        val o = mockk<OptionMapping>(relaxed = true)
        every { o.asString } returns value
        return o
    }

    private fun targetUser(idLong: Long, isBot: Boolean = false): User {
        val u = mockk<User>(relaxed = true)
        every { u.idLong } returns idLong
        every { u.isBot } returns isBot
        return u
    }

    @Test
    fun `delegates to TipService with parsed args`() {
        val sender = UserDto(discordId = senderId, guildId = guildId).apply { socialCredit = 200L }
        val target = targetUser(recipientId)
        every { event.getOption("user") } returns userOpt(target)
        every { event.getOption("amount") } returns intOpt(50L)
        every { event.getOption("message") } returns strOpt("thanks")
        every {
            tipService.tip(senderId, recipientId, guildId, 50L, "thanks", any(), any())
        } returns TipService.TipOutcome.Ok(
            sender = senderId, recipient = recipientId, amount = 50L, note = "thanks",
            senderNewBalance = 150L, recipientNewBalance = 50L,
            sentTodayAfter = 50L, dailyCap = 500L
        )

        command.handle(DefaultCommandContext(event), sender, 5)

        verify(exactly = 1) {
            tipService.tip(senderId, recipientId, guildId, 50L, "thanks", any(), any())
        }
        verify(exactly = 1) {
            userDtoHelper.calculateUserDto(recipientId, guildId, false)
        }
    }

    @Test
    fun `rejects bot recipient before calling service`() {
        val sender = UserDto(discordId = senderId, guildId = guildId).apply { socialCredit = 200L }
        val bot = targetUser(recipientId, isBot = true)
        every { event.getOption("user") } returns userOpt(bot)
        every { event.getOption("amount") } returns intOpt(50L)

        command.handle(DefaultCommandContext(event), sender, 5)

        verify(exactly = 0) { tipService.tip(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `rejects self recipient before calling service`() {
        val sender = UserDto(discordId = senderId, guildId = guildId).apply { socialCredit = 200L }
        val self = targetUser(senderId)
        every { event.getOption("user") } returns userOpt(self)
        every { event.getOption("amount") } returns intOpt(50L)

        command.handle(DefaultCommandContext(event), sender, 5)

        verify(exactly = 0) { tipService.tip(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `renders embed for each TipOutcome variant`() {
        val sender = UserDto(discordId = senderId, guildId = guildId).apply { socialCredit = 200L }
        val target = targetUser(recipientId)
        every { event.getOption("user") } returns userOpt(target)
        every { event.getOption("amount") } returns intOpt(50L)
        every { event.getOption("message") } returns null

        val outcomes = listOf(
            TipService.TipOutcome.Ok(senderId, recipientId, 50L, null, 150L, 50L, 50L, 500L),
            TipService.TipOutcome.InvalidAmount(10L, 500L),
            TipService.TipOutcome.InvalidRecipient(TipService.TipOutcome.InvalidRecipient.Reason.SELF),
            TipService.TipOutcome.InsufficientCredits(have = 30L, needed = 50L),
            TipService.TipOutcome.DailyCapExceeded(sentToday = 480L, cap = 500L, attempted = 50L),
            TipService.TipOutcome.UnknownSender,
            TipService.TipOutcome.UnknownRecipient,
        )

        outcomes.forEach { variant ->
            every {
                tipService.tip(senderId, recipientId, guildId, 50L, null, any(), any())
            } returns variant

            // Should not throw on any variant.
            command.handle(DefaultCommandContext(event), sender, 5)
        }
    }
}
