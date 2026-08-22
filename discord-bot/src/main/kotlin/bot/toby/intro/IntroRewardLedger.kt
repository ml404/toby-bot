package bot.toby.intro

import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

/**
 * Which intro plays are owed a reward, and which have already collected.
 *
 * The intro-play credit and XP used to be paid the moment the join handler had
 * *asked* for an intro. `playUserIntro` returns as soon as `loadAndPlayIntro`
 * is invoked and loading is asynchronous, so "we asked" is all the return
 * value ever meant: a dead link or a stream that died paid out exactly the
 * same as a clip everybody heard. The reward was the only feedback a member
 * got, and it said the opposite of the truth.
 *
 * The join path now records an expectation here and the reward is paid when
 * [IntroPlayedEvent] arrives — which, since the previous change, means audio
 * actually ran. Two useful things fall out of the expectation being written
 * only by the join path:
 *
 *  - a play started any other way (the **View intros** button, `/play intro`)
 *    redeems nothing, so the button cannot be pressed for credit;
 *  - an expectation that is never redeemed simply expires, so a failure needs
 *    no unwinding.
 */
@Service
class IntroRewardLedger {

    private val expected = Caffeine.newBuilder()
        // Comfortably longer than a load plus the longest clip, and short
        // enough that an intro which never played cannot pay out later.
        .expireAfterWrite(EXPIRY_MINUTES, TimeUnit.MINUTES)
        .maximumSize(10_000)
        .build<String, Boolean>()

    /** A join asked for this intro; pay for it if and when it plays. */
    fun expect(introId: String) {
        expected.put(introId, true)
    }

    /**
     * @return true at most once per expectation, so a repeat of the same
     *   [IntroPlayedEvent] cannot pay twice.
     */
    fun redeem(introId: String): Boolean = expected.asMap().remove(introId) != null

    companion object {
        private const val EXPIRY_MINUTES = 2L
    }
}
