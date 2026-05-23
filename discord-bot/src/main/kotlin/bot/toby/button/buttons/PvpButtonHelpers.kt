package bot.toby.button.buttons

import database.service.PvpWagerService
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent

/**
 * Cross-game button helpers for the PvP mini-games (`/rps`,
 * `/tictactoe`, future `/connect4`). Holds the bits that are
 * identical between the per-game `*Button.kt` handlers — the
 * "match already resolved" ephemeral reply and the
 * [PvpWagerService.AcceptOutcome] → user-facing-text mapping.
 *
 * Per-game button code (the routing switch, the move/pick handlers,
 * the win-embed rendering) stays in the per-game `*Button.kt`.
 */
object PvpButtonHelpers {

    /**
     * Ephemeral "this match already resolved or expired" reply, surfaced
     * when a click lands on a session the registry no longer has —
     * either it timed out, was forfeited, or another race already
     * resolved it.
     */
    fun ephemeralAlreadyResolved(event: ButtonInteractionEvent) {
        event.hook.sendMessage("This match already resolved or expired.").setEphemeral(true).queue()
    }

    /** Per-outcome text shown on the accept-error embed when [PvpWagerService.debitBoth] fails. */
    fun describeAccept(outcome: PvpWagerService.AcceptOutcome): String = when (outcome) {
        is PvpWagerService.AcceptOutcome.InitiatorInsufficient ->
            "The challenger no longer has enough credits to cover the stake."
        is PvpWagerService.AcceptOutcome.OpponentInsufficient ->
            "You no longer have enough credits (have ${outcome.have}, need ${outcome.needed})."
        PvpWagerService.AcceptOutcome.UnknownInitiator ->
            "We couldn't find the challenger's profile."
        PvpWagerService.AcceptOutcome.UnknownOpponent ->
            "We couldn't find your profile."
        is PvpWagerService.AcceptOutcome.Ok -> "" // never surfaced
    }
}
