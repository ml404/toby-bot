package web.template

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Guards the script-loading contract for every casino minigame page.
 *
 * Before this test landed, six of nine minigame templates (slots,
 * coinflip, dice, highlow, scratch, roulette) silently never loaded
 * `casino-render.js` — which meant the chip-stack flourish on a win
 * never fired in production for those games. Each template was
 * supposed to add `<script src="/js/casino-render.js">` after the
 * shared fragment, and most authors forgot. The fix was to hoist
 * casino-render into the shared `~{fragments/casinoMinigame ::
 * scripts}` block; this test pins that hoist in place so the next
 * minigame author can't accidentally reintroduce the gap.
 *
 * Two assertions per template:
 *   1. The template uses the shared fragment include. A new minigame
 *      that hand-rolls its own script block (as keno did before this
 *      cleanup) misses every shared module added since: sounds,
 *      jackpot pool banner, balance flourish, win-settle, dom helpers,
 *      anti-autoclicker telemetry. The fragment is the single source
 *      of truth for "what scripts a casino game needs".
 *   2. The template does NOT load casino-render.js explicitly. If the
 *      shared fragment already loads it (which it does, post-#427's
 *      successor PR), an explicit re-load is dead weight and a
 *      subtle hint that the author copy-pasted from a stale example.
 *
 * Scope is the per-game minigame templates only — lobby pages
 * (poker-lobby, blackjack-lobby) deliberately don't include the
 * minigame fragment because they're picker pages.
 */
class CasinoMinigameTemplatesTest {

    private val templatesRoot: Path = Paths.get("src/main/resources/templates")
        .takeIf { Files.exists(it) }
        ?: Paths.get("web/src/main/resources/templates")

    private val minigameTemplates = listOf(
        "slots.html",
        "coinflip.html",
        "dice.html",
        "highlow.html",
        "scratch.html",
        "keno.html",
        "roulette.html",
        "baccarat.html",
        "casinoholdem-solo.html",
    )

    /**
     * Casino pages that hand-roll their script list rather than including
     * the shared `casinoMinigame :: scripts` fragment. They have a custom
     * page flow (their own state-poll loop, no `TobyCasinoGame.init`
     * scaffolding, no `{prefix}-bet`/`{prefix}-stake` DOM contract) so
     * pulling in the full fragment would load five modules they never
     * call. The trade-off is that their script load is opt-in: the next
     * shared module they depend on has to be wired by hand. The test
     * below pins the two scripts the celebratory win flourish needs.
     *
     * Regression for "no coins visible on /blackjack/{g}/solo": the
     * refactor that swapped `CasinoRender.flashChipsOn` for
     * `TobyCasinoWinSettle.fire` in blackjack-solo.js hoisted
     * casino-win-settle.js into the shared fragment but never added
     * the matching <script> tag to blackjack-solo.html — the chip
     * flourish silently no-op'd at runtime because the helper was
     * undefined.
     */
    private val bespokeCasinoTemplates = listOf(
        "blackjack-solo.html",
        "blackjack-table.html",
    )

    @Test
    fun `every minigame template includes the shared casinoMinigame scripts fragment`() {
        val missing = minigameTemplates.filter { name ->
            val path = templatesRoot.resolve(name)
            assertTrue(Files.exists(path), "missing template: $path")
            !Files.readString(path).contains("fragments/casinoMinigame :: scripts")
        }
        assertTrue(
            missing.isEmpty(),
            "These minigame templates do not include the shared " +
                "`fragments/casinoMinigame :: scripts` block, so they are " +
                "missing every shared module added to the fragment " +
                "(casino-sounds, casino-render, casino-win-settle, " +
                "casino-game, casino-bot-suspicion, etc.). Replace the " +
                "ad-hoc <script> block with " +
                "`<th:block th:replace=\"~{fragments/casinoMinigame :: scripts}\"></th:block>`: " +
                "$missing"
        )
    }

    @Test
    fun `no minigame template loads casino-render explicitly (it lives in the shared fragment)`() {
        val redundant = minigameTemplates.filter { name ->
            val path = templatesRoot.resolve(name)
            Files.readString(path).contains("/js/casino-render.js")
        }
        assertTrue(
            redundant.isEmpty(),
            "These minigame templates load `casino-render.js` explicitly. " +
                "It now lives in the shared `casinoMinigame :: scripts` fragment, so " +
                "an explicit reload is dead weight (and a hint the template " +
                "was copy-pasted from a pre-fix example): $redundant"
        )
    }

    @Test
    fun `shared fragment loads every script a minigame depends on`() {
        // Belt-and-braces: pin the script list in the fragment so
        // dropping one won't go unnoticed. The order matters too —
        // win-settle reads CasinoSounds + CasinoRender, game reads
        // win-settle, dom is read by per-game IIFEs at module load,
        // bot-suspicion is read by the games that opt in.
        val fragmentPath = templatesRoot.resolve("fragments/casinoMinigame.html")
        assertTrue(Files.exists(fragmentPath), "missing fragment: $fragmentPath")
        val content = Files.readString(fragmentPath)

        val required = listOf(
            "/js/api.js",
            "/js/toasts.js",
            "/js/casino-sounds.js",
            "/js/casino-jackpot.js",
            "/js/casino-balance.js",
            "/js/casino-topup.js",
            "/js/casino-result.js",
            "/js/casino-render.js",
            "/js/casino-win-settle.js",
            "/js/casino-game.js",
            "/js/casino-minigame-dom.js",
            "/js/casino-bot-suspicion.js",
        )
        val missing = required.filter { !content.contains(it) }
        assertTrue(
            missing.isEmpty(),
            "Shared casino-minigame fragment is missing required scripts: $missing. " +
                "If a script was deliberately removed, update this test alongside " +
                "the per-game expectation it implies."
        )

        // Order check: render must come before win-settle (helper reads
        // it), win-settle before game (helper auto-fires from inside
        // game.js's renderResult chain).
        val renderIdx = content.indexOf("casino-render.js")
        val winSettleIdx = content.indexOf("casino-win-settle.js")
        val gameIdx = content.indexOf("casino-game.js")
        assertFalse(
            renderIdx > winSettleIdx,
            "casino-render.js must load before casino-win-settle.js"
        )
        assertFalse(
            winSettleIdx > gameIdx,
            "casino-win-settle.js must load before casino-game.js"
        )
    }

    @Test
    fun `bespoke casino pages load casino-render and casino-win-settle in order`() {
        val required = listOf("/js/casino-render.js", "/js/casino-win-settle.js")
        bespokeCasinoTemplates.forEach { name ->
            val path = templatesRoot.resolve(name)
            assertTrue(Files.exists(path), "missing template: $path")
            val content = Files.readString(path)
            required.forEach { script ->
                assertTrue(
                    content.contains(script),
                    "$name does not load $script — the chip-stack flourish " +
                        "(TobyCasinoWinSettle.fire) silently no-ops without it. " +
                        "Add `<script th:src=\"@{$script}\"></script>` to the " +
                        "page's script block."
                )
            }
            val renderIdx = content.indexOf("casino-render.js")
            val settleIdx = content.indexOf("casino-win-settle.js")
            assertTrue(
                renderIdx in 0 until settleIdx,
                "$name must load casino-render.js before casino-win-settle.js " +
                    "(the helper reads window.CasinoRender at fire-time)."
            )
        }
    }
}
