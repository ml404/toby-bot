package web.service

import common.connect4.Connect4Engine
import common.rps.RpsEngine
import common.tictactoe.TicTacToeEngine
import database.connect4.Connect4SessionRegistry
import database.duel.PendingDuelRegistry
import database.duel.RecentDuelResolutions
import database.dto.UserDto
import database.rps.RpsSessionRegistry
import database.service.UserService
import database.tictactoe.TicTacToeSessionRegistry
import org.springframework.stereotype.Service

/**
 * Read-only projection helpers around the in-memory PvP session
 * registries for the web UI, plus a lazy-create for the opponent's
 * [UserDto] row. Each game gets prefixed methods (`duel*`, `rps*`,
 * `ticTacToe*`, `connect4*`) so the parallel projections don't
 * collide. Shared shape lives in [PvpParticipant] / [PvpParticipantPair]
 * so the six repeated identity fields aren't restated per game.
 */
@Service
class PvpWebService(
    private val pendingDuelRegistry: PendingDuelRegistry,
    private val rpsSessionRegistry: RpsSessionRegistry,
    private val ticTacToeSessionRegistry: TicTacToeSessionRegistry,
    private val connect4SessionRegistry: Connect4SessionRegistry,
    private val userService: UserService,
    private val memberLookup: MemberLookupHelper,
    private val recentDuelResolutions: RecentDuelResolutions,
) {

    /**
     * Display data for one side of a head-to-head match. Avatar URL is
     * nullable — JDA `Member.effectiveAvatarUrl` returns the Discord
     * default when no custom avatar is set, and we keep that as a
     * sentinel-null so the JS can render its own initial fallback.
     */
    data class PvpParticipant(
        // Stringified Discord snowflake — 18 digits exceed JS Number
        // precision so a numeric round-trip would render the wrong id.
        val discordId: String,
        val name: String,
        val avatarUrl: String?,
    )

    /**
     * Common shape for "two participants + stake + when it started".
     * Composed (not inherited) into the per-game pending / session
     * views so the six repeated fields live in one place.
     */
    data class PvpParticipantPair(
        val initiator: PvpParticipant,
        val opponent: PvpParticipant,
        val stake: Long,
        val createdAtEpochSeconds: Long,
    )

    /**
     * Project two Discord ids into a [PvpParticipantPair]. Falls back to
     * `Player XXXX` when the member isn't resolvable (e.g. left the
     * guild between offer and view).
     */
    private fun pair(
        guildId: Long,
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        stake: Long,
        createdAtEpochSeconds: Long,
    ): PvpParticipantPair {
        val members = memberLookup.resolveAll(guildId, listOf(initiatorDiscordId, opponentDiscordId))
        val initiator = members[initiatorDiscordId]
        val opponent = members[opponentDiscordId]
        return PvpParticipantPair(
            initiator = PvpParticipant(
                discordId = initiatorDiscordId.toString(),
                name = initiator?.name ?: memberLookup.fallbackName(initiatorDiscordId),
                avatarUrl = initiator?.avatarUrl,
            ),
            opponent = PvpParticipant(
                discordId = opponentDiscordId.toString(),
                name = opponent?.name ?: memberLookup.fallbackName(opponentDiscordId),
                avatarUrl = opponent?.avatarUrl,
            ),
            stake = stake,
            createdAtEpochSeconds = createdAtEpochSeconds,
        )
    }
    data class PendingDuelView(
        val duelId: Long,
        // Stringified so the 18-digit Discord snowflake survives JS's 53-bit
        // Number precision. pvp.js renders these into the row text directly,
        // so a numeric round-trip would print a rounded id.
        val initiatorDiscordId: String,
        val initiatorName: String,
        val initiatorAvatarUrl: String?,
        val opponentDiscordId: String,
        val opponentName: String,
        val opponentAvatarUrl: String?,
        val stake: Long,
        val createdAtEpochSeconds: Long,
    )

    fun duelPendingForOpponent(discordId: Long, guildId: Long): List<PendingDuelView> {
        val rows = pendingDuelRegistry.pendingForOpponent(discordId, guildId)
        return projectDuel(rows, guildId)
    }

    fun duelPendingForInitiator(discordId: Long, guildId: Long): List<PendingDuelView> {
        val rows = pendingDuelRegistry.pendingForInitiator(discordId, guildId)
        return projectDuel(rows, guildId)
    }

    data class ResolutionView(
        val initiatorDiscordId: String,
        val initiatorName: String,
        val initiatorAvatarUrl: String?,
        val opponentDiscordId: String,
        val opponentName: String,
        val opponentAvatarUrl: String?,
        val winnerDiscordId: String,
        val pot: Long,
        val lossTribute: Long,
    )

    /** Combined `/outgoing` payload: still-pending offers plus any
     *  just-resolved duels the initiator hasn't seen yet (read-once;
     *  consumed from [RecentDuelResolutions]). */
    data class OutgoingPayload(
        val pending: List<PendingDuelView>,
        val resolutions: List<ResolutionView>,
    )

    fun duelOutgoingPayload(discordId: Long, guildId: Long): OutgoingPayload {
        val pending = duelPendingForInitiator(discordId, guildId)
        val resolved = recentDuelResolutions.consumeForInitiator(discordId, guildId)
        if (resolved.isEmpty()) return OutgoingPayload(pending, emptyList())
        val ids = resolved.flatMapTo(HashSet()) { listOf(it.initiatorDiscordId, it.opponentDiscordId) }
        val members = memberLookup.resolveAll(guildId, ids)
        val resolutions = resolved.map { r ->
            val initiator = members[r.initiatorDiscordId]
            val opponent = members[r.opponentDiscordId]
            ResolutionView(
                initiatorDiscordId = r.initiatorDiscordId.toString(),
                initiatorName = initiator?.name ?: memberLookup.fallbackName(r.initiatorDiscordId),
                initiatorAvatarUrl = initiator?.avatarUrl,
                opponentDiscordId = r.opponentDiscordId.toString(),
                opponentName = opponent?.name ?: memberLookup.fallbackName(r.opponentDiscordId),
                opponentAvatarUrl = opponent?.avatarUrl,
                winnerDiscordId = r.winnerDiscordId.toString(),
                pot = r.pot,
                lossTribute = r.lossTribute,
            )
        }
        return OutgoingPayload(pending, resolutions)
    }

    private fun projectDuel(rows: List<PendingDuelRegistry.PendingDuel>, guildId: Long): List<PendingDuelView> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.flatMapTo(HashSet()) { listOf(it.initiatorDiscordId, it.opponentDiscordId) }
        val members = memberLookup.resolveAll(guildId, ids)
        return rows.map { d ->
            val initiator = members[d.initiatorDiscordId]
            val opponent = members[d.opponentDiscordId]
            PendingDuelView(
                duelId = d.id,
                initiatorDiscordId = d.initiatorDiscordId.toString(),
                initiatorName = initiator?.name ?: memberLookup.fallbackName(d.initiatorDiscordId),
                initiatorAvatarUrl = initiator?.avatarUrl,
                opponentDiscordId = d.opponentDiscordId.toString(),
                opponentName = opponent?.name ?: memberLookup.fallbackName(d.opponentDiscordId),
                opponentAvatarUrl = opponent?.avatarUrl,
                stake = d.stake,
                createdAtEpochSeconds = d.createdAt.epochSecond,
            )
        }
    }

    fun ensureOpponent(opponentDiscordId: Long, guildId: Long): UserDto {
        return userService.getUserById(opponentDiscordId, guildId)
            ?: userService.createNewUser(UserDto(opponentDiscordId, guildId))
    }

    // ─── RPS ──────────────────────────────────────────────────────────

    /** Pending RPS offer (PENDING state — not yet accepted). */
    data class RpsPendingView(
        val sessionId: Long,
        val participants: PvpParticipantPair,
    )

    /**
     * RPS session in any state. PENDING shows just the offer; LIVE adds
     * pick-flags (whether each side has submitted) without revealing
     * the actual choice — that's only exposed in the resolution
     * response from `/pick`.
     */
    data class RpsSessionView(
        val sessionId: Long,
        val participants: PvpParticipantPair,
        val state: String,
        val iPicked: Boolean,
        val opponentPicked: Boolean,
        val expiresAtEpochSeconds: Long?,
    )

    fun rpsPendingForOpponent(discordId: Long, guildId: Long): List<RpsPendingView> =
        rpsSessionRegistry.pendingForOpponent(discordId, guildId).map { projectRpsPending(it, guildId) }

    fun rpsPendingForInitiator(discordId: Long, guildId: Long): List<RpsPendingView> =
        rpsSessionRegistry.pendingForInitiator(discordId, guildId).map { projectRpsPending(it, guildId) }

    fun rpsActiveFor(viewerDiscordId: Long, guildId: Long): List<RpsSessionView> =
        rpsSessionRegistry.liveFor(viewerDiscordId, guildId).map { projectRpsSession(it, viewerDiscordId) }

    /** Single-session view scoped to [viewerDiscordId]; null when the
     *  session no longer exists or the viewer isn't a participant. */
    fun rpsSessionView(sessionId: Long, viewerDiscordId: Long): RpsSessionView? {
        val session = rpsSessionRegistry.get(sessionId) ?: return null
        if (viewerDiscordId != session.initiatorDiscordId && viewerDiscordId != session.opponentDiscordId) return null
        return projectRpsSession(session, viewerDiscordId)
    }

    private fun projectRpsPending(session: RpsSessionRegistry.Session, guildId: Long): RpsPendingView =
        RpsPendingView(
            sessionId = session.id,
            participants = pair(
                guildId = guildId,
                initiatorDiscordId = session.initiatorDiscordId,
                opponentDiscordId = session.opponentDiscordId,
                stake = session.stake,
                createdAtEpochSeconds = session.createdAt.epochSecond,
            ),
        )

    private fun projectRpsSession(session: RpsSessionRegistry.Session, viewerDiscordId: Long): RpsSessionView {
        val opponentDiscordId = if (viewerDiscordId == session.initiatorDiscordId)
            session.opponentDiscordId else session.initiatorDiscordId
        val pickTtlSeconds = rpsSessionRegistry.pickTtl.seconds
        val expiresAt = if (session.state == RpsSessionRegistry.Session.State.LIVE)
            session.createdAt.epochSecond + pickTtlSeconds else null
        return RpsSessionView(
            sessionId = session.id,
            participants = pair(
                guildId = session.guildId,
                initiatorDiscordId = session.initiatorDiscordId,
                opponentDiscordId = session.opponentDiscordId,
                stake = session.stake,
                createdAtEpochSeconds = session.createdAt.epochSecond,
            ),
            state = session.state.name,
            iPicked = session.picks.containsKey(viewerDiscordId),
            opponentPicked = session.picks.containsKey(opponentDiscordId),
            expiresAtEpochSeconds = expiresAt,
        )
    }

    /**
     * Shared "match resolved" outcome shape. The per-game
     * `ResolveOutcome` sealed types translate to this at the controller
     * boundary so the JS always sees the same JSON shape regardless of
     * game. `choices` is RPS-specific (null for TTT/C4 when added).
     */
    data class PvpResolutionOutcome(
        val verdict: String, // "WIN", "DRAW", "REFUND"
        val winnerDiscordId: String?,
        val loserDiscordId: String?,
        val stake: Long,
        val pot: Long,
        val winnerNewBalance: Long?,
        val loserNewBalance: Long?,
        val initiatorNewBalance: Long?,
        val opponentNewBalance: Long?,
        val lossTribute: Long?,
        val initiatorChoice: String?,
        val opponentChoice: String?,
    ) {
        companion object {
            fun rpsWin(o: database.service.RpsService.ResolveOutcome.Win, initiatorDiscordId: Long): PvpResolutionOutcome {
                val initiatorWon = o.winnerDiscordId == initiatorDiscordId
                return PvpResolutionOutcome(
                    verdict = "WIN",
                    winnerDiscordId = o.winnerDiscordId.toString(),
                    loserDiscordId = o.loserDiscordId.toString(),
                    stake = o.stake,
                    pot = o.pot,
                    winnerNewBalance = o.winnerNewBalance,
                    loserNewBalance = o.loserNewBalance,
                    initiatorNewBalance = null,
                    opponentNewBalance = null,
                    lossTribute = o.lossTribute,
                    initiatorChoice = (if (initiatorWon) o.winnerChoice else o.loserChoice).name,
                    opponentChoice = (if (initiatorWon) o.loserChoice else o.winnerChoice).name,
                )
            }

            fun rpsDraw(o: database.service.RpsService.ResolveOutcome.Draw): PvpResolutionOutcome =
                PvpResolutionOutcome(
                    verdict = "DRAW",
                    winnerDiscordId = null, loserDiscordId = null,
                    stake = o.stake, pot = 0L,
                    winnerNewBalance = null, loserNewBalance = null,
                    initiatorNewBalance = o.initiatorNewBalance,
                    opponentNewBalance = o.opponentNewBalance,
                    lossTribute = null,
                    initiatorChoice = o.choice.name,
                    opponentChoice = o.choice.name,
                )

            fun rpsDoubleRefund(o: database.service.RpsService.ResolveOutcome.DoubleRefund): PvpResolutionOutcome =
                PvpResolutionOutcome(
                    verdict = "REFUND",
                    winnerDiscordId = null, loserDiscordId = null,
                    stake = o.stake, pot = 0L,
                    winnerNewBalance = null, loserNewBalance = null,
                    initiatorNewBalance = o.initiatorNewBalance,
                    opponentNewBalance = o.opponentNewBalance,
                    lossTribute = null,
                    initiatorChoice = null,
                    opponentChoice = null,
                )

            /** TTT + C4 share [TurnBasedBoardWagerService.ResolveOutcome.Win];
             *  the same factory translates both. RPS keeps its own because
             *  it carries the two choices in the win-outcome. */
            fun boardWin(o: database.boardgame.TurnBasedBoardWagerService.ResolveOutcome.Win): PvpResolutionOutcome =
                PvpResolutionOutcome(
                    verdict = "WIN",
                    winnerDiscordId = o.winnerDiscordId.toString(),
                    loserDiscordId = o.loserDiscordId.toString(),
                    stake = o.stake, pot = o.pot,
                    winnerNewBalance = o.winnerNewBalance,
                    loserNewBalance = o.loserNewBalance,
                    initiatorNewBalance = null, opponentNewBalance = null,
                    lossTribute = o.lossTribute,
                    initiatorChoice = null, opponentChoice = null,
                )

            fun boardDraw(o: database.boardgame.TurnBasedBoardWagerService.ResolveOutcome.Draw): PvpResolutionOutcome =
                PvpResolutionOutcome(
                    verdict = "DRAW",
                    winnerDiscordId = null, loserDiscordId = null,
                    stake = o.stake, pot = 0L,
                    winnerNewBalance = null, loserNewBalance = null,
                    initiatorNewBalance = o.initiatorNewBalance,
                    opponentNewBalance = o.opponentNewBalance,
                    lossTribute = null,
                    initiatorChoice = null, opponentChoice = null,
                )
        }
    }

    // ─── TicTacToe ────────────────────────────────────────────────────

    data class TicTacToePendingView(
        val sessionId: Long,
        val participants: PvpParticipantPair,
    )

    /**
     * Tic-Tac-Toe session view. `cells` is the row-major 9-element
     * board: "X", "O", or `null` per cell. `myMark` is "X" for the
     * initiator, "O" for the opponent — and `null` if the viewer
     * somehow isn't a participant (defensive; the controller filters).
     */
    data class TicTacToeSessionView(
        val sessionId: Long,
        val participants: PvpParticipantPair,
        val state: String,
        val cells: List<String?>,
        val currentActorDiscordId: String?,
        val myMark: String?,
        val myTurn: Boolean,
        val winningLine: List<Int>?,
        val winner: String?,
        val moveExpiresAtEpochSeconds: Long?,
    )

    fun ticTacToePendingForOpponent(discordId: Long, guildId: Long): List<TicTacToePendingView> =
        ticTacToeSessionRegistry.pendingForOpponent(discordId, guildId).map { projectTicTacToePending(it) }

    fun ticTacToePendingForInitiator(discordId: Long, guildId: Long): List<TicTacToePendingView> =
        ticTacToeSessionRegistry.pendingForInitiator(discordId, guildId).map { projectTicTacToePending(it) }

    fun ticTacToeActiveFor(viewerDiscordId: Long, guildId: Long): List<TicTacToeSessionView> =
        ticTacToeSessionRegistry.liveFor(viewerDiscordId, guildId).map { projectTicTacToeSession(it, viewerDiscordId) }

    fun ticTacToeSessionView(sessionId: Long, viewerDiscordId: Long): TicTacToeSessionView? {
        val session = ticTacToeSessionRegistry.get(sessionId) ?: return null
        if (viewerDiscordId != session.initiatorDiscordId && viewerDiscordId != session.opponentDiscordId) return null
        return projectTicTacToeSession(session, viewerDiscordId)
    }

    private fun projectTicTacToePending(session: TicTacToeSessionRegistry.Session): TicTacToePendingView =
        TicTacToePendingView(
            sessionId = session.id,
            participants = pair(
                guildId = session.guildId,
                initiatorDiscordId = session.initiatorDiscordId,
                opponentDiscordId = session.opponentDiscordId,
                stake = session.stake,
                createdAtEpochSeconds = session.createdAt.epochSecond,
            ),
        )

    private fun projectTicTacToeSession(
        session: TicTacToeSessionRegistry.Session,
        viewerDiscordId: Long,
    ): TicTacToeSessionView {
        val moveTtlSeconds = ticTacToeSessionRegistry.moveTtl.seconds
        val moveExpires = if (session.state == database.boardgame.TurnBasedBoardSessionRegistry.Session.State.LIVE) {
            session.createdAt.epochSecond + (moveTtlSeconds * (session.moveNumber + 1))
        } else null
        val myMark = session.markFor(viewerDiscordId)
        return TicTacToeSessionView(
            sessionId = session.id,
            participants = pair(
                guildId = session.guildId,
                initiatorDiscordId = session.initiatorDiscordId,
                opponentDiscordId = session.opponentDiscordId,
                stake = session.stake,
                createdAtEpochSeconds = session.createdAt.epochSecond,
            ),
            state = session.state.name,
            cells = session.board.cells.map { it?.name },
            currentActorDiscordId = if (session.state == database.boardgame.TurnBasedBoardSessionRegistry.Session.State.LIVE)
                session.currentActorDiscordId().toString() else null,
            myMark = myMark?.name,
            myTurn = session.state == database.boardgame.TurnBasedBoardSessionRegistry.Session.State.LIVE &&
                myMark != null && myMark == session.currentTurn,
            winningLine = session.winningLine,
            winner = session.winner?.name,
            moveExpiresAtEpochSeconds = moveExpires,
        )
    }

    // ─── Connect 4 ────────────────────────────────────────────────────

    data class Connect4PendingView(
        val sessionId: Long,
        val participants: PvpParticipantPair,
    )

    /** Same shape as [TicTacToeSessionView] but with C4-specific marks
     *  ("RED"/"YELLOW") and `lastDroppedRow` / `lastDroppedCol` for
     *  cell-flash highlighting after the most recent drop. */
    data class Connect4SessionView(
        val sessionId: Long,
        val participants: PvpParticipantPair,
        val state: String,
        val cells: List<String?>,
        val currentActorDiscordId: String?,
        val myMark: String?,
        val myTurn: Boolean,
        val winningLine: List<Int>?,
        val winner: String?,
        val lastDroppedRow: Int?,
        val lastDroppedCol: Int?,
        val moveExpiresAtEpochSeconds: Long?,
    )

    fun connect4PendingForOpponent(discordId: Long, guildId: Long): List<Connect4PendingView> =
        connect4SessionRegistry.pendingForOpponent(discordId, guildId).map { projectConnect4Pending(it) }

    fun connect4PendingForInitiator(discordId: Long, guildId: Long): List<Connect4PendingView> =
        connect4SessionRegistry.pendingForInitiator(discordId, guildId).map { projectConnect4Pending(it) }

    fun connect4ActiveFor(viewerDiscordId: Long, guildId: Long): List<Connect4SessionView> =
        connect4SessionRegistry.liveFor(viewerDiscordId, guildId).map { projectConnect4Session(it, viewerDiscordId) }

    fun connect4SessionView(sessionId: Long, viewerDiscordId: Long): Connect4SessionView? {
        val session = connect4SessionRegistry.get(sessionId) ?: return null
        if (viewerDiscordId != session.initiatorDiscordId && viewerDiscordId != session.opponentDiscordId) return null
        return projectConnect4Session(session, viewerDiscordId)
    }

    private fun projectConnect4Pending(session: Connect4SessionRegistry.Session): Connect4PendingView =
        Connect4PendingView(
            sessionId = session.id,
            participants = pair(
                guildId = session.guildId,
                initiatorDiscordId = session.initiatorDiscordId,
                opponentDiscordId = session.opponentDiscordId,
                stake = session.stake,
                createdAtEpochSeconds = session.createdAt.epochSecond,
            ),
        )

    private fun projectConnect4Session(
        session: Connect4SessionRegistry.Session,
        viewerDiscordId: Long,
    ): Connect4SessionView {
        val moveTtlSeconds = connect4SessionRegistry.moveTtl.seconds
        val moveExpires = if (session.state == database.boardgame.TurnBasedBoardSessionRegistry.Session.State.LIVE) {
            session.createdAt.epochSecond + (moveTtlSeconds * (session.moveNumber + 1))
        } else null
        val myMark = session.markFor(viewerDiscordId)
        return Connect4SessionView(
            sessionId = session.id,
            participants = pair(
                guildId = session.guildId,
                initiatorDiscordId = session.initiatorDiscordId,
                opponentDiscordId = session.opponentDiscordId,
                stake = session.stake,
                createdAtEpochSeconds = session.createdAt.epochSecond,
            ),
            state = session.state.name,
            cells = session.board.cells.map { it?.name },
            currentActorDiscordId = if (session.state == database.boardgame.TurnBasedBoardSessionRegistry.Session.State.LIVE)
                session.currentActorDiscordId().toString() else null,
            myMark = myMark?.name,
            myTurn = session.state == database.boardgame.TurnBasedBoardSessionRegistry.Session.State.LIVE &&
                myMark != null && myMark == session.currentTurn,
            winningLine = session.winningLine,
            winner = session.winner?.name,
            lastDroppedRow = session.lastDroppedRow,
            lastDroppedCol = session.lastDroppedCol,
            moveExpiresAtEpochSeconds = moveExpires,
        )
    }

    /** Translate "tictactoe" or "connect4" string body field to cell/column int. */
    fun parseCellIndex(raw: Int?, max: Int): Int? =
        if (raw != null && raw in 0 until max) raw else null

    /** Parse a string from the web request body into the engine enum. */
    fun parseRpsChoice(raw: String?): RpsEngine.Choice? = when (raw?.uppercase()) {
        "ROCK" -> RpsEngine.Choice.ROCK
        "PAPER" -> RpsEngine.Choice.PAPER
        "SCISSORS" -> RpsEngine.Choice.SCISSORS
        else -> null
    }
}
