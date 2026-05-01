package web.casino

import org.springframework.http.ResponseEntity

/**
 * Centralised translation from casino-game failure outcomes
 * (insufficient credits, invalid stake, unknown user, …) and
 * sign-in/membership guard failures into [ResponseEntity] with the
 * controller's per-game response shape.
 *
 * Before this lived as 6+ copies of the same `when (outcome)` arms
 * across DiceController, CoinflipController, SlotsController,
 * ScratchController and HighlowController — the strings were identical
 * but had to be edited in five places. Now there is one place.
 *
 * The mapper is parameterised on the concrete response type so each
 * controller keeps its typed Win/Lose construction; only the failure
 * shape (`{ ok = false, error = "..." }`) is unified. Callers supply
 * an [errorFactory] that wraps a message into their response type, e.g.
 *
 * ```
 * private val errors = CasinoOutcomeMapper { msg -> RollResponse(false, msg) }
 * ```
 */
class CasinoOutcomeMapper<R : CasinoResponseLike>(
    private val errorFactory: (String) -> R,
) {
    /**
     * 401 / 403 builder for [WebGuildAccess.requireMemberForJson].
     * Hand it directly to the `errorBuilder = …` argument.
     */
    val errorBuilder: (Int) -> ResponseEntity<R> = { status ->
        ResponseEntity.status(status).body(errorFactory(memberGuardMessage(status)))
    }

    fun insufficientCredits(stake: Long, have: Long): ResponseEntity<R> =
        ResponseEntity.badRequest().body(errorFactory(insufficientCreditsMessage(stake, have)))

    fun insufficientCoinsForTopUp(needed: Long, have: Long): ResponseEntity<R> =
        ResponseEntity.badRequest().body(errorFactory(insufficientCoinsForTopUpMessage(needed, have)))

    fun invalidStake(min: Long, max: Long): ResponseEntity<R> =
        ResponseEntity.badRequest().body(errorFactory(invalidStakeMessage(min, max)))

    fun unknownUser(): ResponseEntity<R> =
        ResponseEntity.badRequest().body(errorFactory(UNKNOWN_USER))

    fun badRequest(message: String): ResponseEntity<R> =
        ResponseEntity.badRequest().body(errorFactory(message))

    companion object {
        const val NOT_SIGNED_IN = "Not signed in."
        const val NOT_MEMBER = "You are not a member of that server."
        const val UNKNOWN_USER = "No user record yet. Try another TobyBot command first."

        fun memberGuardMessage(status: Int): String =
            if (status == 401) NOT_SIGNED_IN else NOT_MEMBER

        fun insufficientCreditsMessage(stake: Long, have: Long): String =
            "Need $stake credits, you have $have."

        fun insufficientCoinsForTopUpMessage(needed: Long, have: Long): String =
            "Need $needed TOBY to cover the shortfall, you have $have."

        fun invalidStakeMessage(min: Long, max: Long): String =
            "Stake must be between $min and $max credits."
    }
}
