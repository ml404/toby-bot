package bot.toby.button.buttons

import database.dto.UserDto
import database.pvp.PvpSessionRegistry
import database.service.pvp.PvpWagerService
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent

/**
 * Cross-game button helpers for the PvP mini-games (`/rps`,
 * `/tictactoe`, `/connect4`). Holds the bits that would otherwise
 * duplicate verbatim across the per-game `*Button.kt` handlers — the
 * "match already resolved" ephemeral, the access checks for
 * scoped-actor vs match-participant routes, the full `handleDecline`
 * body, and the [PvpWagerService.AcceptOutcome] → user-facing text
 * mapping.
 *
 * Per-game button code (the routing switch, the move/pick handlers,
 * the win-embed rendering, the per-game `resolveAndEdit` that knows
 * the engine's terminal-result shape) stays in the per-game
 * `*Button.kt`.
 */
object PvpButtonHelpers {

    /**
     * Ephemeral "this match already resolved or expired" reply,
     * surfaced when a click lands on a session the registry no longer
     * has — either it timed out, was forfeited, or another race
     * already resolved it.
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

    /**
     * Access check used by the PENDING-phase scoped-actor routes
     * (accept, decline). The accept/decline buttons are scoped to the
     * opponent's discord id — encoded in the button payload at
     * `scopedDiscordId`. Returns true if the click came from the
     * expected actor (so the caller proceeds); false after sending the
     * ephemeral rejection (so the caller returns).
     *
     * [friendlyVerb] is interpolated into the ephemeral message —
     * pass "accept" or "decline" to match the click action.
     */
    fun requireScopedActor(
        event: ButtonInteractionEvent,
        requestingUserDto: UserDto,
        scopedDiscordId: Long,
        friendlyVerb: String,
    ): Boolean {
        if (requestingUserDto.discordId == scopedDiscordId) return true
        event.hook.sendMessage(
            "This isn't your challenge to $friendlyVerb — wait for <@${scopedDiscordId}> to respond."
        ).setEphemeral(true).queue()
        return false
    }

    /**
     * Access check used by the LIVE-phase action routes
     * (pick / place / drop / forfeit). The LIVE buttons accept clicks
     * from either participant — but not from anyone else. Returns true
     * if the clicker is one of the two participants (so the caller
     * proceeds); false after sending [notParticipantMessage] as an
     * ephemeral reply.
     *
     * The message text varies between routes ("can play" for board
     * games, "can pick" for RPS, "to forfeit" for the forfeit route),
     * so the caller supplies the exact text.
     */
    fun requireMatchParticipant(
        event: ButtonInteractionEvent,
        requestingUserDto: UserDto,
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        notParticipantMessage: String,
    ): Boolean {
        if (requestingUserDto.discordId == initiatorDiscordId ||
            requestingUserDto.discordId == opponentDiscordId
        ) {
            return true
        }
        event.hook.sendMessage(notParticipantMessage).setEphemeral(true).queue()
        return false
    }

    /**
     * Full body of the `handleDecline` route — identical across the
     * three PvP games modulo the registry / embeds references. Runs
     * the scoped-actor check, calls [decline] to drop the pending
     * session, then edits the original message with the
     * decline-rendered embed and clears the buttons. If the session
     * already expired or was resolved, an ephemeral
     * "already-resolved" reply is sent in lieu of the embed edit.
     */
    fun <S : PvpSessionRegistry.Session> handleDecline(
        event: ButtonInteractionEvent,
        requestingUserDto: UserDto,
        scopedDiscordId: Long,
        sessionId: Long,
        decline: (Long) -> S?,
        pendingDeclineEmbed: (initiatorDiscordId: Long, opponentDiscordId: Long) -> MessageEmbed,
    ) {
        if (!requireScopedActor(event, requestingUserDto, scopedDiscordId, "decline")) return
        val session = decline(sessionId) ?: run {
            ephemeralAlreadyResolved(event); return
        }
        event.message.editMessageEmbeds(
            pendingDeclineEmbed(session.initiatorDiscordId, session.opponentDiscordId)
        ).setComponents(emptyList<MessageTopLevelComponent>()).queue()
    }
}
