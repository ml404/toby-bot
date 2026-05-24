package bot.toby.command.commands.game.pvp

/**
 * Shared encode/parse for the colon-delimited button-id convention used
 * by every PvP mini-game (`/rps`, `/tictactoe`, `/connect4`, future
 * games). Format:
 *
 *   `<buttonName>:<actionName>:<sessionId>:<payload>`
 *
 *  - `buttonName`: matches [`Button.name`][bot.toby.button.Button.name]
 *    so `DefaultButtonManager` routes by `parts[0]` to the right
 *    handler (`"rps"`, `"tictactoe"`, `"connect4"`).
 *  - `actionName`: the per-game `Action` enum's `name` — the per-game
 *    embeds object owns its own enum + `Action.valueOf(...)` so this
 *    codec stays game-agnostic.
 *  - `sessionId`: the PvP session id allocated by the per-game
 *    registry. Long.
 *  - `payload`: action-dependent — discord id for ACCEPT/DECLINE,
 *    cell/column index for PLACE/DROP, `0` for FORFEIT or for actions
 *    where the clicker is known from the event. Long for uniformity.
 *
 * The button-id wire format is a public contract — un-clicked
 * messages on Discord may sit for days before a click, so any change
 * to the format must remain backwards-compatible. The codec is
 * intentionally permissive on case for the prefix (matches the
 * pre-existing per-game `equals(BUTTON_NAME, ignoreCase = true)`
 * convention).
 */
object PvpButtonIdCodec {

    /**
     * Stage-1 parse result — the codec only knows the wire format, not
     * the per-game `Action` enum. The caller does
     * `Action.valueOf(actionName)` itself so the per-game `ParsedButtonId`
     * stays strongly-typed against its own enum.
     */
    data class ParsedRaw(val actionName: String, val sessionId: Long, val payload: Long)

    fun encode(buttonName: String, actionName: String, sessionId: Long, payload: Long): String =
        listOf(buttonName, actionName, sessionId.toString(), payload.toString())
            .joinToString(":")

    fun parse(componentId: String, buttonName: String): ParsedRaw? {
        val parts = componentId.split(':')
        if (parts.size != 4 || !parts[0].equals(buttonName, ignoreCase = true)) return null
        val sessionId = parts[2].toLongOrNull() ?: return null
        val payload = parts[3].toLongOrNull() ?: return null
        return ParsedRaw(actionName = parts[1], sessionId = sessionId, payload = payload)
    }
}
