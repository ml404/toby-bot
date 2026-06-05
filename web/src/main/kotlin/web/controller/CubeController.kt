package web.controller

import database.service.user.CubeListService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import web.service.CardView
import web.service.CategoryAsFan
import web.service.CategoryGroup
import web.service.CubeResult
import web.service.CubeWebService
import web.service.GenerateData
import web.service.PreviewData
import web.util.discordIdOrNull
import web.util.displayName

/**
 * Public web surface for the MTG cube tool — the same as-fan maths and
 * pack generation as the `/cube` Discord command, no login required (like
 * `/dnd` and `/utils`). GET renders the page. The `/api/...` GET endpoints
 * take a Scryfall query; the POST variants take the user's own pasted card
 * list (too large for a query string) — both return the same JSON.
 *
 * Saving a list is account-bound: the `/api/lists` endpoints require a
 * logged-in Discord user and persist per-user via [CubeListService], so a
 * saved cube follows the user across devices (unlike the rest of the page,
 * which is anonymous).
 */
@Controller
@RequestMapping("/cube")
class CubeController(
    private val cubeWebService: CubeWebService,
    private val cubeLists: CubeListService,
) {

    @GetMapping
    fun page(
        @AuthenticationPrincipal user: OAuth2User?,
        model: Model,
    ): String {
        model.addAttribute("username", user.displayName())
        // Drives the saved-lists UI: only logged-in users can save/load.
        model.addAttribute("loggedIn", user != null)
        return "cube"
    }

    @GetMapping("/api/asfan", produces = ["application/json"])
    @ResponseBody
    fun asFan(
        @RequestParam("total") total: Int,
        @RequestParam("cubeSize") cubeSize: Int,
        @RequestParam("packSize", defaultValue = "15") packSize: Int,
    ): ResponseEntity<CubeAsFanResponse> =
        when (val result = cubeWebService.asFan(total, cubeSize, packSize)) {
            is CubeResult.Success -> ResponseEntity.ok(CubeAsFanResponse(true, null, result.value))
            is CubeResult.Failure -> ResponseEntity.badRequest().body(CubeAsFanResponse(false, result.error, null))
        }

    @GetMapping("/api/preview", produces = ["application/json"])
    @ResponseBody
    fun preview(
        @RequestParam("query") query: String,
        @RequestParam("packSize", defaultValue = "15") packSize: Int,
    ): ResponseEntity<CubePreviewResponse> = previewResponse(cubeWebService.preview(query, packSize))

    @PostMapping("/api/preview", consumes = ["application/json"], produces = ["application/json"])
    @ResponseBody
    fun previewList(
        @RequestBody request: CubeListPreviewRequest,
    ): ResponseEntity<CubePreviewResponse> =
        previewResponse(cubeWebService.previewList(request.list, request.packSize))

    @GetMapping("/api/generate", produces = ["application/json"])
    @ResponseBody
    fun generate(
        @RequestParam("query") query: String,
        @RequestParam("packs", defaultValue = "24") packs: Int,
        @RequestParam("packSize", defaultValue = "15") packSize: Int,
        @RequestParam("balanced", defaultValue = "true") balanced: Boolean,
    ): ResponseEntity<CubeGenerateResponse> =
        generateResponse(cubeWebService.generate(query, packs, packSize, balanced))

    @PostMapping("/api/generate", consumes = ["application/json"], produces = ["application/json"])
    @ResponseBody
    fun generateList(
        @RequestBody request: CubeListGenerateRequest,
    ): ResponseEntity<CubeGenerateResponse> =
        generateResponse(cubeWebService.generateList(request.list, request.packs, request.packSize, request.balanced))

    private fun previewResponse(result: CubeResult<PreviewData>): ResponseEntity<CubePreviewResponse> =
        when (result) {
            is CubeResult.Success -> ResponseEntity.ok(
                CubePreviewResponse(true, null, result.value.poolSize, result.value.groups, result.value.notFound)
            )
            is CubeResult.Failure ->
                ResponseEntity.badRequest().body(CubePreviewResponse(false, result.error, 0, emptyList(), emptyList()))
        }

    private fun generateResponse(result: CubeResult<GenerateData>): ResponseEntity<CubeGenerateResponse> =
        when (result) {
            is CubeResult.Success -> ResponseEntity.ok(
                CubeGenerateResponse(
                    ok = true,
                    error = null,
                    poolSize = result.value.poolSize,
                    packCount = result.value.packCount,
                    packSize = result.value.packSize,
                    packs = result.value.packs,
                    distribution = result.value.distribution,
                    notFound = result.value.notFound,
                )
            )
            is CubeResult.Failure -> ResponseEntity.badRequest().body(
                CubeGenerateResponse(false, result.error, 0, 0, 0, emptyList(), emptyList(), emptyList())
            )
        }

    // --- Saved lists (account-bound; require a logged-in Discord user) ---

    @GetMapping("/api/lists", produces = ["application/json"])
    @ResponseBody
    fun savedLists(@AuthenticationPrincipal user: OAuth2User?): ResponseEntity<List<SavedCubeList>> {
        val discordId = user?.discordIdOrNull() ?: return ResponseEntity.status(401).build()
        val rows = cubeLists.listForUser(discordId).map {
            SavedCubeList(it.name, it.cards, it.updatedAt.toString())
        }
        return ResponseEntity.ok(rows)
    }

    @PutMapping("/api/lists", consumes = ["application/json"], produces = ["application/json"])
    @ResponseBody
    fun saveList(
        @RequestBody request: SaveCubeListRequest,
        @AuthenticationPrincipal user: OAuth2User?,
    ): ResponseEntity<SavedCubeList> {
        val discordId = user?.discordIdOrNull() ?: return ResponseEntity.status(401).build()
        val name = request.name.trim()
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH || request.cards.isBlank()) {
            return ResponseEntity.badRequest().build()
        }
        // Cap how many cubes one account can hoard; overwriting an existing
        // name is always allowed.
        if (cubeLists.get(discordId, name) == null && cubeLists.listForUser(discordId).size >= MAX_LISTS_PER_USER) {
            return ResponseEntity.status(409).build()
        }
        val row = cubeLists.save(discordId, name, request.cards)
        return ResponseEntity.ok(SavedCubeList(row.name, row.cards, row.updatedAt.toString()))
    }

    @DeleteMapping("/api/lists")
    @ResponseBody
    fun deleteList(
        @RequestParam("name") name: String,
        @AuthenticationPrincipal user: OAuth2User?,
    ): ResponseEntity<Void> {
        val discordId = user?.discordIdOrNull() ?: return ResponseEntity.status(401).build()
        cubeLists.delete(discordId, name)
        return ResponseEntity.noContent().build()
    }

    private companion object {
        const val MAX_NAME_LENGTH = 100
        const val MAX_LISTS_PER_USER = 50
    }
}

data class CubeListPreviewRequest(val list: String = "", val packSize: Int = 15)

data class CubeListGenerateRequest(
    val list: String = "",
    val packs: Int = 24,
    val packSize: Int = 15,
    val balanced: Boolean = true,
)

data class SaveCubeListRequest(val name: String = "", val cards: String = "")

data class SavedCubeList(val name: String, val cards: String, val updatedAt: String)

data class CubeAsFanResponse(val ok: Boolean, val error: String?, val value: Double?)

data class CubePreviewResponse(
    val ok: Boolean,
    val error: String?,
    val poolSize: Int,
    val groups: List<CategoryGroup>,
    val notFound: List<String> = emptyList(),
)

data class CubeGenerateResponse(
    val ok: Boolean,
    val error: String?,
    val poolSize: Int,
    val packCount: Int,
    val packSize: Int,
    val packs: List<List<CardView>>,
    val distribution: List<CategoryAsFan>,
    val notFound: List<String> = emptyList(),
)
