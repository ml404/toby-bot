package bot.toby.intro

import bot.toby.handler.VoiceEventHandler
import database.service.leveling.XpAwardService
import database.service.social.SocialCreditAwardService
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The intro-play reward used to be paid the moment the join handler had
 * *asked* for an intro. Loading is asynchronous, so that paid a dead link
 * exactly what it paid a clip everybody heard — and the reward was the only
 * feedback the member got.
 */
class IntroPlayRewardServiceTest {

    private lateinit var ledger: IntroRewardLedger
    private lateinit var awardService: SocialCreditAwardService
    private lateinit var xpAwardService: XpAwardService
    private lateinit var service: IntroPlayRewardService

    @BeforeEach
    fun setUp() {
        ledger = IntroRewardLedger()
        awardService = mockk(relaxed = true)
        xpAwardService = mockk(relaxed = true)
        service = IntroPlayRewardService(ledger, awardService, xpAwardService)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `an intro a join asked for is paid once it has played`() {
        ledger.expect("7_1_1")

        service.onIntroPlayed(IntroPlayedEvent("7_1_1"))

        verify(exactly = 1) {
            awardService.award(1L, 7L, VoiceEventHandler.INTRO_PLAY_CREDIT, "intro-play", any(), any())
        }
        verify(exactly = 1) {
            xpAwardService.award(1L, 7L, VoiceEventHandler.INTRO_PLAY_XP, "intro-play", any(), any(), any())
        }
    }

    @Test
    fun `an intro that never played is never paid`() {
        // No redemption to make: the expectation simply expires, so a failure
        // needs no unwinding.
        service.onIntroPlayed(IntroPlayedEvent("7_1_1"))

        verify(exactly = 0) { awardService.award(any(), any(), any(), "intro-play", any(), any()) }
    }

    @Test
    fun `pressing the play button earns nothing`() {
        // Only the join path writes an expectation, so a play started any
        // other way redeems nothing however often it is pressed.
        repeat(5) { service.onIntroPlayed(IntroPlayedEvent("7_1_1")) }

        verify(exactly = 0) { awardService.award(any(), any(), any(), "intro-play", any(), any()) }
    }

    @Test
    fun `one expectation pays exactly once`() {
        ledger.expect("7_1_1")

        repeat(4) { service.onIntroPlayed(IntroPlayedEvent("7_1_1")) }

        verify(exactly = 1) { awardService.award(any(), any(), any(), "intro-play", any(), any()) }
    }

    @Test
    fun `the member and server come off the intro id`() {
        ledger.expect("990_55_2")

        service.onIntroPlayed(IntroPlayedEvent("990_55_2"))

        verify(exactly = 1) {
            awardService.award(55L, 990L, VoiceEventHandler.INTRO_PLAY_CREDIT, "intro-play", any(), any())
        }
    }

    @Test
    fun `an unreadable id is dropped rather than paid to nobody`() {
        ledger.expect("nonsense")

        service.onIntroPlayed(IntroPlayedEvent("nonsense"))

        verify(exactly = 0) { awardService.award(any(), any(), any(), "intro-play", any(), any()) }
    }

    @Test
    fun `an award that throws does not travel back into playback`() {
        // This runs from a Spring listener on the audio path.
        ledger.expect("7_1_1")
        every {
            awardService.award(any(), any(), any(), "intro-play", any(), any())
        } throws IllegalStateException("db down")

        service.onIntroPlayed(IntroPlayedEvent("7_1_1"))
    }

    @Test
    fun `redeeming is one-shot`() {
        ledger.expect("7_1_1")

        assertTrue(ledger.redeem("7_1_1"))
        assertTrue(!ledger.redeem("7_1_1"))
    }
}
