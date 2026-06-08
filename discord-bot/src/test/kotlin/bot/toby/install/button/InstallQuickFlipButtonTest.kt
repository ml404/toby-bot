package bot.toby.install.button

import bot.toby.install.InstallWizard
import common.casino.coinflip.Coinflip
import database.dto.user.UserDto
import database.service.casino.coinflip.CoinflipService
import database.service.casino.coinflip.CoinflipService.FlipOutcome
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.entities.MessageEmbed
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class InstallQuickFlipButtonTest {

    private lateinit var coinflipService: CoinflipService
    private lateinit var button: InstallQuickFlipButton
    private lateinit var fx: InstallButtonFixture

    @BeforeEach
    fun setUp() {
        coinflipService = mockk(relaxed = true)
        button = InstallQuickFlipButton(coinflipService)
        fx = InstallButtonFixture()
    }

    @Test
    fun `name matches the launcher quick-flip id`() {
        assertEquals(InstallWizard.BTN_QUICK_FLIP, button.name)
    }

    @Test
    fun `defersReply is true so each clicker gets an ephemeral result`() {
        assertEquals(true, button.defersReply)
    }

    @Test
    fun `flips the minimum stake on heads for the clicking user, with no owner gate`() {
        // A non-owner must still be able to play their own flip.
        fx.asNonOwner()
        val requestingUser = mockk<UserDto> { every { discordId } returns 42L }
        every {
            coinflipService.flip(42L, any(), Coinflip.MIN_STAKE, Coinflip.Side.HEADS, any(), any(), any(), any())
        } returns FlipOutcome.Win(
            stake = Coinflip.MIN_STAKE,
            payout = Coinflip.MIN_STAKE * 2,
            net = Coinflip.MIN_STAKE,
            landed = Coinflip.Side.HEADS,
            predicted = Coinflip.Side.HEADS,
            newBalance = 110L,
        )

        button.handle(fx.ctx, requestingUser, 0)

        verify(exactly = 1) {
            coinflipService.flip(42L, any(), Coinflip.MIN_STAKE, Coinflip.Side.HEADS, any(), any(), any(), any())
        }
        verify(exactly = 1) { fx.hook.sendMessageEmbeds(any<MessageEmbed>(), *anyVararg<MessageEmbed>()) }
        verify(exactly = 0) { fx.event.reply(any<String>()) }
    }
}
