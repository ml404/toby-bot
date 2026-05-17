package web.controller

import core.music.MusicControlGateway
import database.service.MusicPlaylistService.PlaylistNameTakenException
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import web.service.MusicSseService
import web.service.MusicWebService
import web.util.WebGuildAccess
import web.util.discordIdOrNull
import web.util.discordIdString
import web.util.displayName

@Controller
@RequestMapping("/music-player")
class MusicWebController(
    private val musicWebService: MusicWebService,
    private val sseService: MusicSseService,
) {

    data class ApiResult(val ok: Boolean, val message: String? = null)
    data class LoadRequest(val query: String = "")
    data class SkipRequest(val count: Int = 1)
    data class VolumeRequest(val volume: Int = 100)
    data class SeekRequest(val positionMs: Long = 0)
    data class LoopRequest(val looping: Boolean = false)
    data class ReorderRequest(val from: Int = 0, val to: Int = 0)
    data class SavePlaylistRequest(val name: String = "")
    data class SavePlaylistResponse(val ok: Boolean, val id: Long? = null, val message: String? = null)

    @GetMapping("/guilds")
    fun guildList(
        @RegisteredOAuth2AuthorizedClient("discord") client: OAuth2AuthorizedClient,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
    ): String {
        val accessToken = client.accessToken.tokenValue
        val discordId = user.discordIdOrNull() ?: return "redirect:/login"
        val guilds = musicWebService.listGuildsForUser(accessToken, discordId)
        model.addAttribute("guilds", guilds)
        model.addAttribute("username", user.displayName())
        model.addAttribute("discordId", user.discordIdString())
        return "music-guilds"
    }

    @GetMapping("/{guildId}")
    fun dashboard(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes,
    ): String = WebGuildAccess.requireForPage(
        user = user,
        guildId = guildId,
        ra = ra,
        lobbyPath = "/music-player/guilds",
        check = musicWebService::isMember,
    ) { discordId ->
        val guildName = musicWebService.getGuildName(guildId) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return@requireForPage "redirect:/music-player/guilds"
        }
        model.addAttribute("guildId", guildId)
        model.addAttribute("guildName", guildName)
        model.addAttribute("username", user.displayName())
        model.addAttribute("discordId", discordId.toString())
        "music-player"
    }

    data class StateResponse(val state: MusicControlGateway.GuildPlayerState? = null, val error: String? = null)

    @GetMapping("/{guildId}/state")
    @ResponseBody
    fun state(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User?,
    ): ResponseEntity<StateResponse> =
        guarded(user, guildId, { StateResponse(error = "Unauthorized") }) {
            val state = musicWebService.getState(guildId)
                ?: return@guarded ResponseEntity.status(404).body(StateResponse(error = "No state"))
            ResponseEntity.ok(StateResponse(state = state))
        }

    @GetMapping("/{guildId}/events")
    fun stream(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User?,
    ): ResponseEntity<SseEmitter> {
        val discordId = user?.discordIdOrNull() ?: return ResponseEntity.status(401).build()
        if (!musicWebService.isMember(discordId, guildId)) return ResponseEntity.status(403).build()
        return ResponseEntity.ok(sseService.register(guildId))
    }

    @PostMapping("/{guildId}/load")
    @ResponseBody
    fun load(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User?,
        @RequestBody body: LoadRequest,
    ): ResponseEntity<ApiResult> = guard(user, guildId) { discordId ->
        if (body.query.isBlank()) {
            return@guard ResponseEntity.badRequest().body(ApiResult(false, "Query is required"))
        }
        val result = musicWebService.load(guildId, body.query, discordId)
        ResponseEntity.ok(ApiResult(result.ok, result.message))
    }

    @GetMapping("/{guildId}/search")
    @ResponseBody
    fun search(
        @PathVariable guildId: Long,
        @RequestParam("q") query: String,
        @AuthenticationPrincipal user: OAuth2User?,
    ): ResponseEntity<List<MusicControlGateway.TrackInfo>> =
        guarded(user, guildId, ::emptyList) {
            if (query.isBlank()) return@guarded ResponseEntity.ok(emptyList())
            ResponseEntity.ok(musicWebService.search(guildId, query))
        }

    @PostMapping("/{guildId}/pause")
    @ResponseBody
    fun pause(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User?,
    ): ResponseEntity<ApiResult> = guard(user, guildId) {
        ResponseEntity.ok(ApiResult(musicWebService.pause(guildId)))
    }

    @PostMapping("/{guildId}/resume")
    @ResponseBody
    fun resume(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User?,
    ): ResponseEntity<ApiResult> = guard(user, guildId) {
        ResponseEntity.ok(ApiResult(musicWebService.resume(guildId)))
    }

    @PostMapping("/{guildId}/skip")
    @ResponseBody
    fun skip(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User?,
        @RequestBody body: SkipRequest,
    ): ResponseEntity<ApiResult> = guard(user, guildId) {
        if (body.count <= 0) {
            return@guard ResponseEntity.badRequest().body(ApiResult(false, "count must be >= 1"))
        }
        ResponseEntity.ok(ApiResult(musicWebService.skip(guildId, body.count)))
    }

    @PostMapping("/{guildId}/stop")
    @ResponseBody
    fun stop(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User?,
    ): ResponseEntity<ApiResult> = guard(user, guildId) {
        ResponseEntity.ok(ApiResult(musicWebService.stop(guildId)))
    }

    @PostMapping("/{guildId}/volume")
    @ResponseBody
    fun volume(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User?,
        @RequestBody body: VolumeRequest,
    ): ResponseEntity<ApiResult> = guard(user, guildId) {
        if (body.volume < 0 || body.volume > 150) {
            return@guard ResponseEntity.badRequest().body(ApiResult(false, "volume must be 0..150"))
        }
        ResponseEntity.ok(ApiResult(musicWebService.setVolume(guildId, body.volume)))
    }

    @PostMapping("/{guildId}/seek")
    @ResponseBody
    fun seek(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User?,
        @RequestBody body: SeekRequest,
    ): ResponseEntity<ApiResult> = guard(user, guildId) {
        if (body.positionMs < 0) {
            return@guard ResponseEntity.badRequest().body(ApiResult(false, "positionMs must be >= 0"))
        }
        ResponseEntity.ok(ApiResult(musicWebService.seek(guildId, body.positionMs)))
    }

    @PostMapping("/{guildId}/loop")
    @ResponseBody
    fun loop(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User?,
        @RequestBody body: LoopRequest,
    ): ResponseEntity<ApiResult> = guard(user, guildId) {
        ResponseEntity.ok(ApiResult(musicWebService.setLooping(guildId, body.looping)))
    }

    @PostMapping("/{guildId}/queue/reorder")
    @ResponseBody
    fun reorder(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User?,
        @RequestBody body: ReorderRequest,
    ): ResponseEntity<ApiResult> = guard(user, guildId) {
        if (body.from < 0 || body.to < 0) {
            return@guard ResponseEntity.badRequest().body(ApiResult(false, "indices must be >= 0"))
        }
        val ok = musicWebService.reorderQueue(guildId, body.from, body.to)
        if (ok) ResponseEntity.ok(ApiResult(true))
        else ResponseEntity.badRequest().body(ApiResult(false, "Invalid indices"))
    }

    @DeleteMapping("/{guildId}/queue/{index}")
    @ResponseBody
    fun removeQueueItem(
        @PathVariable guildId: Long,
        @PathVariable index: Int,
        @AuthenticationPrincipal user: OAuth2User?,
    ): ResponseEntity<ApiResult> = guard(user, guildId) {
        val removed = musicWebService.removeFromQueue(guildId, index)
        if (removed != null) ResponseEntity.ok(ApiResult(true))
        else ResponseEntity.status(404).body(ApiResult(false, "No track at index $index"))
    }

    @GetMapping("/{guildId}/playlists")
    @ResponseBody
    fun listPlaylists(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User?,
    ): ResponseEntity<List<MusicWebService.PlaylistSummary>> =
        guarded(user, guildId, ::emptyList) {
            ResponseEntity.ok(musicWebService.listPlaylistsForGuild(guildId))
        }

    @PostMapping("/{guildId}/playlists")
    @ResponseBody
    fun createPlaylist(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User?,
        @RequestBody body: SavePlaylistRequest,
    ): ResponseEntity<SavePlaylistResponse> =
        guardedWrite(user, guildId, { SavePlaylistResponse(false, null, "Unauthorized") }) { discordId ->
            if (body.name.isBlank()) {
                return@guardedWrite ResponseEntity.badRequest()
                    .body(SavePlaylistResponse(false, null, "Name is required"))
            }
            try {
                val id = musicWebService.saveCurrentQueueAsPlaylist(guildId, discordId, body.name)
                ResponseEntity.ok(SavePlaylistResponse(true, id, null))
            } catch (e: PlaylistNameTakenException) {
                ResponseEntity.status(409).body(SavePlaylistResponse(false, null, e.message))
            } catch (e: IllegalArgumentException) {
                ResponseEntity.badRequest().body(SavePlaylistResponse(false, null, e.message))
            } catch (e: IllegalStateException) {
                ResponseEntity.badRequest().body(SavePlaylistResponse(false, null, e.message))
            }
        }

    @PostMapping("/{guildId}/playlists/{playlistId}/load")
    @ResponseBody
    fun loadPlaylist(
        @PathVariable guildId: Long,
        @PathVariable playlistId: Long,
        @AuthenticationPrincipal user: OAuth2User?,
    ): ResponseEntity<ApiResult> = guard(user, guildId) { discordId ->
        val result = musicWebService.loadPlaylistIntoQueue(guildId, playlistId, discordId)
        if (result.ok) ResponseEntity.ok(ApiResult(true, "Loaded ${result.tracksLoaded} tracks (${result.tracksFailed} failed)"))
        else ResponseEntity.status(404).body(ApiResult(false, result.message ?: "Failed to load playlist"))
    }

    @DeleteMapping("/{guildId}/playlists/{playlistId}")
    @ResponseBody
    fun deletePlaylist(
        @PathVariable guildId: Long,
        @PathVariable playlistId: Long,
        @AuthenticationPrincipal user: OAuth2User?,
    ): ResponseEntity<ApiResult> = guard(user, guildId) { discordId ->
        val ok = musicWebService.deletePlaylist(playlistId, discordId, isGuildAdmin = false)
        if (ok) ResponseEntity.ok(ApiResult(true))
        else ResponseEntity.status(403).body(ApiResult(false, "Only the owner can delete this playlist"))
    }

    /**
     * Read-only auth wrapper — guild membership is enough. Used by `/state`,
     * `/events`, `/search`, `/playlists` (GET). Generic [T] lets each caller
     * pick its own response body shape; [errorBody] supplies the body for
     * 401 / 403 so the shape matches the endpoint's success response.
     */
    private inline fun <T : Any> guarded(
        user: OAuth2User?,
        guildId: Long,
        errorBody: () -> T,
        block: (discordId: Long) -> ResponseEntity<T>,
    ): ResponseEntity<T> = WebGuildAccess.requireForJson(
        user = user,
        guildId = guildId,
        check = musicWebService::isMember,
        errorBuilder = { status -> ResponseEntity.status(status).body(errorBody()) },
        block = block,
    )

    /**
     * Write-flavoured auth wrapper — requires guild membership AND the
     * caller's per-guild `musicPermission` flag (the same gate every music
     * slash command uses). Revoking music via `/adjust` or the moderation
     * UI cuts off the web dashboard too.
     */
    private inline fun <T : Any> guardedWrite(
        user: OAuth2User?,
        guildId: Long,
        errorBody: () -> T,
        block: (discordId: Long) -> ResponseEntity<T>,
    ): ResponseEntity<T> = WebGuildAccess.requireForJson(
        user = user,
        guildId = guildId,
        check = musicWebService::canControlMusic,
        errorBuilder = { status -> ResponseEntity.status(status).body(errorBody()) },
        block = block,
    )

    private inline fun guard(
        user: OAuth2User?,
        guildId: Long,
        block: (discordId: Long) -> ResponseEntity<ApiResult>,
    ): ResponseEntity<ApiResult> = guardedWrite(user, guildId, { ApiResult(false, "Unauthorized") }, block)
}
