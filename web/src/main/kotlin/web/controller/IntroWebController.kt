package web.controller

import database.dto.MusicDto
import database.service.MusicFileService
import jakarta.servlet.http.HttpSession
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import web.service.IntroWebService
import web.util.discordIdOrNull
import web.util.discordIdString
import web.util.displayName

@Controller
@RequestMapping("/intro")
class IntroWebController(
    private val introWebService: IntroWebService,
    private val musicFileService: MusicFileService,
    @param:Value($$"${spring.security.oauth2.client.registration.discord.client-id}")
    private val discordClientId: String
) {
    private val inviteUrl: String
        get() = "https://discord.com/api/oauth2/authorize?client_id=$discordClientId&permissions=8&scope=bot%20applications.commands"

    @GetMapping("/guilds")
    fun guildList(
        @RegisteredOAuth2AuthorizedClient("discord") client: OAuth2AuthorizedClient,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model
    ): String {
        val accessToken = client.accessToken.tokenValue
        val discordId = user.discordIdOrNull()
        val guilds = introWebService.getMutualGuilds(accessToken)

        val counts: Map<String, Int> = if (discordId != null) {
            introWebService.getIntroCountsForGuilds(discordId, guilds.map { it.id.toLong() })
                .mapKeys { it.key.toString() }
        } else emptyMap()

        model.addAttribute("guilds", guilds)
        model.addAttribute("introCounts", counts)
        model.addAttribute("maxIntros", IntroWebService.MAX_INTRO_COUNT)
        model.addAttribute("username", user.displayName())
        model.addAttribute("discordId", user.discordIdString())
        model.addAttribute("inviteUrl", inviteUrl)

        return "guilds"
    }

    @GetMapping("/{guildId}")
    fun introPage(
        @PathVariable guildId: Long,
        @RequestParam(required = false) targetDiscordId: Long?,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes
    ): String {
        val authDiscordId = user.discordIdOrNull()
            ?: return "redirect:/intro/guilds"

        val guildName = introWebService.getGuildName(guildId) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return "redirect:/intro/guilds"
        }

        val isSuperUser = introWebService.isSuperUser(authDiscordId, guildId)
        val effectiveDiscordId = if (isSuperUser && targetDiscordId != null) targetDiscordId else authDiscordId
        val members = if (isSuperUser) introWebService.getGuildMembers(guildId) else emptyList()

        val intros = introWebService.getUserIntros(effectiveDiscordId, guildId)

        model.addAttribute("guildId", guildId)
        model.addAttribute("guildName", guildName)
        model.addAttribute("intros", intros)
        model.addAttribute("username", user.displayName())
        model.addAttribute("atLimit", intros.size >= IntroWebService.MAX_INTRO_COUNT)
        model.addAttribute("maxIntros", IntroWebService.MAX_INTRO_COUNT)
        model.addAttribute("maxFileKb", IntroWebService.MAX_FILE_SIZE / 1024)
        model.addAttribute("maxDurationSeconds", IntroWebService.MAX_INTRO_DURATION_SECONDS)
        model.addAttribute("isSuperUser", isSuperUser)
        model.addAttribute("members", members)
        model.addAttribute("targetDiscordId", if (isSuperUser) targetDiscordId else null)
        model.addAttribute("effectiveDiscordId", effectiveDiscordId)

        return "intros"
    }

    @PostMapping("/{guildId}/set")
    fun setIntro(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        @RequestParam inputType: String,
        @RequestParam(required = false) url: String?,
        @RequestParam(required = false) file: MultipartFile?,
        @RequestParam(defaultValue = "90") volume: Int,
        @RequestParam(required = false) replaceIndex: Int?,
        @RequestParam(required = false) targetDiscordId: Long?,
        @RequestParam(required = false) startMs: Int?,
        @RequestParam(required = false) endMs: Int?,
        ra: RedirectAttributes
    ): String {
        val authDiscordId = user.discordIdOrNull()
            ?: return "redirect:/intro/guilds"
        val effectiveDiscordId = resolveEffectiveDiscordId(authDiscordId, guildId, targetDiscordId)

        val clampedVolume = volume.coerceIn(1, 100)
        val error: String? = when (inputType) {
            "url" -> introWebService.setIntroByUrl(
                effectiveDiscordId, guildId, url.orEmpty().trim(), clampedVolume, replaceIndex, startMs, endMs
            )
            "file" -> if (file != null && !file.isEmpty) {
                introWebService.setIntroByFile(
                    effectiveDiscordId, guildId, file, clampedVolume, replaceIndex, startMs, endMs
                )
            } else {
                "No file provided."
            }
            else -> "Invalid input type."
        }

        if (error != null) {
            ra.addFlashAttribute("error", error)
        } else {
            ra.addFlashAttribute("success", "Intro saved successfully.")
        }

        return redirectToIntroPage(guildId, authDiscordId, targetDiscordId)
    }

    @PostMapping("/{guildId}/delete/{introId:.+}")
    fun deleteIntro(
        @PathVariable guildId: Long,
        @PathVariable introId: String,
        @AuthenticationPrincipal user: OAuth2User,
        @RequestParam(required = false) targetDiscordId: Long?,
        session: HttpSession,
        ra: RedirectAttributes
    ): String {
        val authDiscordId = user.discordIdOrNull()
            ?: return "redirect:/intro/guilds"
        val effectiveDiscordId = resolveEffectiveDiscordId(authDiscordId, guildId, targetDiscordId)

        // Snapshot the DTO before deletion so undo can restore it.
        val snapshot = musicFileService.getMusicFileById(introId)?.let {
            DeletedIntroSnapshot(
                id = it.id.orEmpty(),
                index = it.index ?: 1,
                fileName = it.fileName,
                introVolume = it.introVolume ?: 90,
                musicBlob = it.musicBlob?.copyOf(),
                startMs = it.startMs,
                endMs = it.endMs,
                discordId = effectiveDiscordId,
                guildId = guildId,
                timestampMs = System.currentTimeMillis()
            )
        }

        val error = introWebService.deleteIntro(effectiveDiscordId, guildId, introId)
        if (error != null) {
            ra.addFlashAttribute("error", error)
        } else {
            if (snapshot != null) session.setAttribute(undoKey(guildId, effectiveDiscordId), snapshot)
            ra.addFlashAttribute("success", "Intro deleted.")
            ra.addFlashAttribute("undoDelete", true)
        }

        return redirectToIntroPage(guildId, authDiscordId, targetDiscordId)
    }

    @PostMapping("/{guildId}/undo-delete")
    fun undoDelete(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        @RequestParam(required = false) targetDiscordId: Long?,
        session: HttpSession,
        ra: RedirectAttributes
    ): String {
        val authDiscordId = user.discordIdOrNull()
            ?: return "redirect:/intro/guilds"
        val effectiveDiscordId = resolveEffectiveDiscordId(authDiscordId, guildId, targetDiscordId)

        val key = undoKey(guildId, effectiveDiscordId)
        val snapshot = session.getAttribute(key) as? DeletedIntroSnapshot
        session.removeAttribute(key)

        if (snapshot == null) {
            ra.addFlashAttribute("error", "Nothing to undo.")
            return redirectToIntroPage(guildId, authDiscordId, targetDiscordId)
        }

        // 10 minute undo window
        if (System.currentTimeMillis() - snapshot.timestampMs > 10 * 60 * 1000) {
            ra.addFlashAttribute("error", "Undo window has expired.")
            return redirectToIntroPage(guildId, authDiscordId, targetDiscordId)
        }

        val dbUser = introWebService.getOrCreateUser(effectiveDiscordId, guildId)
        if (dbUser.musicDtos.size >= IntroWebService.MAX_INTRO_COUNT) {
            ra.addFlashAttribute("error", "Cannot restore — intro limit already reached.")
            return redirectToIntroPage(guildId, authDiscordId, targetDiscordId)
        }

        val targetIndex = if (dbUser.musicDtos.none { it.index == snapshot.index }) {
            snapshot.index
        } else {
            (dbUser.musicDtos.map { it.index ?: 0 }.maxOrNull() ?: 0) + 1
        }
        val restored = MusicDto(
            dbUser,
            targetIndex,
            snapshot.fileName,
            snapshot.introVolume,
            snapshot.musicBlob,
            snapshot.startMs,
            snapshot.endMs
        )
        musicFileService.createNewMusicFile(restored)

        ra.addFlashAttribute("success", "Intro restored.")
        return redirectToIntroPage(guildId, authDiscordId, targetDiscordId)
    }

    @PostMapping("/{guildId}/update-volume", consumes = ["application/json"])
    @ResponseBody
    fun updateVolume(
        @PathVariable guildId: Long,
        @RequestBody body: UpdateVolumeRequest,
        @AuthenticationPrincipal user: OAuth2User,
        @RequestParam(required = false) targetDiscordId: Long?
    ): ResponseEntity<ApiResult> {
        val authDiscordId = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(ApiResult(false, "Not signed in."))
        val effective = resolveEffectiveDiscordId(authDiscordId, guildId, targetDiscordId)
        val error = introWebService.updateIntroVolume(effective, guildId, body.introId, body.volume)
        return if (error != null) ResponseEntity.badRequest().body(ApiResult(false, error))
        else ResponseEntity.ok(ApiResult(true, null))
    }

    @PostMapping("/{guildId}/update-timestamps", consumes = ["application/json"])
    @ResponseBody
    fun updateTimestamps(
        @PathVariable guildId: Long,
        @RequestBody body: UpdateTimestampsRequest,
        @AuthenticationPrincipal user: OAuth2User,
        @RequestParam(required = false) targetDiscordId: Long?
    ): ResponseEntity<ApiResult> {
        val authDiscordId = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(ApiResult(false, "Not signed in."))
        val effective = resolveEffectiveDiscordId(authDiscordId, guildId, targetDiscordId)
        val error = introWebService.updateIntroTimestamps(effective, guildId, body.introId, body.startMs, body.endMs)
        return if (error != null) ResponseEntity.badRequest().body(ApiResult(false, error))
        else ResponseEntity.ok(ApiResult(true, null))
    }

    @PostMapping("/{guildId}/update-name", consumes = ["application/json"])
    @ResponseBody
    fun updateName(
        @PathVariable guildId: Long,
        @RequestBody body: UpdateNameRequest,
        @AuthenticationPrincipal user: OAuth2User,
        @RequestParam(required = false) targetDiscordId: Long?
    ): ResponseEntity<ApiResult> {
        val authDiscordId = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(ApiResult(false, "Not signed in."))
        val effective = resolveEffectiveDiscordId(authDiscordId, guildId, targetDiscordId)
        val error = introWebService.updateIntroName(effective, guildId, body.introId, body.name)
        return if (error != null) ResponseEntity.badRequest().body(ApiResult(false, error))
        else ResponseEntity.ok(ApiResult(true, null))
    }

    @PostMapping("/{guildId}/reorder", consumes = ["application/json"])
    @ResponseBody
    fun reorder(
        @PathVariable guildId: Long,
        @RequestBody body: ReorderRequest,
        @AuthenticationPrincipal user: OAuth2User,
        @RequestParam(required = false) targetDiscordId: Long?
    ): ResponseEntity<ApiResult> {
        val authDiscordId = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(ApiResult(false, "Not signed in."))
        val effective = resolveEffectiveDiscordId(authDiscordId, guildId, targetDiscordId)
        val error = introWebService.reorderIntros(effective, guildId, body.orderedIds)
        return if (error != null) ResponseEntity.badRequest().body(ApiResult(false, error))
        else ResponseEntity.ok(ApiResult(true, null))
    }

    @GetMapping("/preview")
    @ResponseBody
    fun preview(@RequestParam url: String): ResponseEntity<PreviewResponse> {
        if (url.isBlank()) return ResponseEntity.badRequest()
            .body(PreviewResponse(false, null, null, null, null, "URL is required."))
        val preview = introWebService.fetchYouTubePreview(url.trim())
            ?: return ResponseEntity.ok(
                PreviewResponse(true, null, null, null, null, "Not a YouTube URL — title/duration unavailable.")
            )
        val tooLong = preview.durationSeconds != null && preview.durationSeconds > IntroWebService.MAX_INTRO_DURATION_SECONDS
        return ResponseEntity.ok(
            PreviewResponse(
                ok = !tooLong,
                videoId = preview.videoId,
                title = preview.title,
                thumbnailUrl = preview.thumbnailUrl,
                durationSeconds = preview.durationSeconds,
                error = if (tooLong) "Video is too long (${preview.durationSeconds}s). Max allowed is ${IntroWebService.MAX_INTRO_DURATION_SECONDS}s." else null
            )
        )
    }

    private fun resolveEffectiveDiscordId(authDiscordId: Long, guildId: Long, targetDiscordId: Long?): Long {
        if (targetDiscordId == null || targetDiscordId == authDiscordId) return authDiscordId
        return if (introWebService.isSuperUser(authDiscordId, guildId)) targetDiscordId else authDiscordId
    }

    private fun redirectToIntroPage(guildId: Long, authDiscordId: Long, targetDiscordId: Long?): String {
        return if (targetDiscordId != null && targetDiscordId != authDiscordId) {
            "redirect:/intro/$guildId?targetDiscordId=$targetDiscordId"
        } else {
            "redirect:/intro/$guildId"
        }
    }

    private fun undoKey(guildId: Long, discordId: Long) = "undoDelete:$guildId:$discordId"
}

data class UpdateVolumeRequest(val introId: String = "", val volume: Int = 0)
data class UpdateNameRequest(val introId: String = "", val name: String = "")
data class UpdateTimestampsRequest(val introId: String = "", val startMs: Int? = null, val endMs: Int? = null)
data class ReorderRequest(val orderedIds: List<String> = emptyList())

data class ApiResult(val ok: Boolean, val error: String?)

data class PreviewResponse(
    val ok: Boolean,
    val videoId: String?,
    val title: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Int?,
    val error: String?
)

data class DeletedIntroSnapshot(
    val id: String,
    val index: Int,
    val fileName: String?,
    val introVolume: Int,
    val musicBlob: ByteArray?,
    val startMs: Int?,
    val endMs: Int?,
    val discordId: Long,
    val guildId: Long,
    val timestampMs: Long
) : java.io.Serializable
