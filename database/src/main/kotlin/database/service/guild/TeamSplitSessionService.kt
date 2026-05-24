package database.service.guild

import database.dto.guild.TeamSplitSessionDto
import java.time.Duration
import java.util.UUID

interface TeamSplitSessionService {

    /**
     * Persist a fresh preview session. Caller passes the resolved
     * roster, the chosen team count, the initial assignments, and the
     * human team names — all locked at modal-submission time. Returns
     * the saved DTO (its `id` is the UUID embedded in button ids).
     */
    fun createSession(
        guildId: Long,
        requesterDiscordId: Long,
        memberIds: List<Long>,
        teamCount: Int,
        assignments: List<List<Long>>,
        teamNames: List<String>,
    ): TeamSplitSessionDto

    fun getSession(id: UUID): TeamSplitSessionDto?

    /**
     * Re-roll: replace assignments only. Members, team count, and names
     * stay frozen at create time so Confirm uses the labels the user
     * originally saw.
     */
    fun updateAssignments(id: UUID, assignments: List<List<Long>>): TeamSplitSessionDto?

    fun markConfirmed(id: UUID): TeamSplitSessionDto?
    fun markCancelled(id: UUID): TeamSplitSessionDto?

    /** Returns the number of rows deleted. */
    fun purgeOlderThan(maxAge: Duration): Int

    fun recentForGuild(guildId: Long, limit: Int): List<TeamSplitSessionDto>
}

/** Encode `[[1,2],[3,4,5]]` as `"1,2|3,4,5"`. */
fun encodeAssignments(assignments: List<List<Long>>): String =
    assignments.joinToString("|") { team -> team.joinToString(",") }

/** Inverse of [encodeAssignments]. Empty/blank input yields an empty list. */
fun decodeAssignments(encoded: String): List<List<Long>> =
    if (encoded.isBlank()) emptyList()
    else encoded.split('|').map { group ->
        group.split(',').mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() }?.toLongOrNull() }
    }

/** Encode `["Red","Blue"]` as `"Red\nBlue"` (names may contain commas). */
fun encodeTeamNames(names: List<String>): String = names.joinToString("\n")

fun decodeTeamNames(encoded: String): List<String> =
    if (encoded.isEmpty()) emptyList() else encoded.split('\n')
