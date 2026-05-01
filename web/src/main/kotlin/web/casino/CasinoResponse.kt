package web.casino

/**
 * Marker for casino-game JSON response DTOs that share the
 * `{ ok, error, ... }` envelope. Implemented by every per-game response
 * (RollResponse, FlipResponse, SpinResponse, ScratchResponse,
 * StartResponse, PlayResponse) so [CasinoOutcomeMapper] can build their
 * error variants generically.
 *
 * Game-specific success fields stay on the concrete data class — only
 * the failure shape needs to be unified.
 */
interface CasinoResponseLike {
    val ok: Boolean
    val error: String?
}
