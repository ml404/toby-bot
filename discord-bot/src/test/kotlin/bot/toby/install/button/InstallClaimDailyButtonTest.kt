package bot.toby.install.button

import bot.toby.install.InstallWizard
import database.dto.user.UserDto
import database.service.social.LoginStreakService
import database.service.social.LoginStreakService.ClaimResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.entities.MessageEmbed
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class InstallClaimDailyButtonTest {

    private lateinit var loginStreakService: LoginStreakService
    private lateinit var button: InstallClaimDailyButton
    private lateinit var fx: InstallButtonFixture

    @BeforeEach
    fun setUp() {
        loginStreakService = mockk(relaxed = true)
        button = InstallClaimDailyButton(loginStreakService)
        fx = InstallButtonFixture()
    }

    @Test
    fun `name matches the launcher claim-daily id`() {
        assertEquals(InstallWizard.BTN_CLAIM_DAILY, button.name)
    }

    @Test
    fun `defersReply is true so each clicker gets an ephemeral result`() {
        assertEquals(true, button.defersReply)
    }

    @Test
    fun `claims the clicking user's daily and replies, with no owner gate`() {
        // A non-owner must still be able to claim their own reward.
        fx.asNonOwner()
        val requestingUser = mockk<UserDto> { every { discordId } returns 42L }
        every { loginStreakService.claim(42L, any(), any(), any()) } returns
            ClaimResult.Granted(
                currentStreak = 1,
                longestStreak = 1,
                xpGranted = 25L,
                creditsGranted = 50L,
                isNewBest = true,
            )

        button.handle(fx.ctx, requestingUser, 0)

        verify(exactly = 1) { loginStreakService.claim(42L, any(), any(), any()) }
        verify(exactly = 1) { fx.hook.sendMessageEmbeds(any<MessageEmbed>(), *anyVararg<MessageEmbed>()) }
        verify(exactly = 0) { fx.event.reply(any<String>()) }
    }
}
