package common.pvp.rps

/**
 * Pure logic for Rock-Paper-Scissors outcomes. No JDA / Spring / DB —
 * tested as a plain function so the game rules can't drift.
 */
object RpsEngine {

    enum class Choice { ROCK, PAPER, SCISSORS }

    sealed interface Outcome {
        /** First player wins. */
        data object FirstWins : Outcome

        /** Second player wins. */
        data object SecondWins : Outcome

        /** Both picked the same move. */
        data object Draw : Outcome
    }

    /**
     * Resolves a complete match. [first] is the move the *first* player
     * (the inviter) chose; [second] is the opponent's choice. Returns
     * the outcome from the first player's perspective so callers don't
     * have to remember who's who.
     */
    fun resolve(first: Choice, second: Choice): Outcome = when {
        first == second -> Outcome.Draw
        beats(first, second) -> Outcome.FirstWins
        else -> Outcome.SecondWins
    }

    private fun beats(a: Choice, b: Choice): Boolean = when (a) {
        Choice.ROCK -> b == Choice.SCISSORS
        Choice.PAPER -> b == Choice.ROCK
        Choice.SCISSORS -> b == Choice.PAPER
    }
}
