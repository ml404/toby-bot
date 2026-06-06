package web.controller

import common.mtg.MtgCommandRef
import common.mtg.MtgCurrency
import database.dto.user.CardPriceWatchDto
import database.service.user.CardPriceWatchService
import database.service.user.CubeListService
import database.service.user.SharedCubeService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import web.service.AnalyticsView
import web.service.CardView
import web.service.CategoryAsFan
import web.service.CardLookupView
import web.service.CategoryGroup
import web.service.CubeResult
import web.service.DiffLineView
import web.service.CubeWebService
import web.service.ComboView
import web.service.RuleView
import web.service.RulingView
import web.service.SetView
import web.service.GenerateData
import web.service.PreviewData
import web.util.discordIdOrNull
import web.util.displayName

/**
 * Public web surface for the Magic toolkit at `/magic` — the same as-fan
 * maths, pack generation, card lookups, legality, reference and price watches
 * as the Discord commands, no login required (like `/dnd` and `/utils`). The
 * page (the `magic` template) is served here along with the shared-cube deep
 * links and the `/magic/api/...` JSON endpoints. The `/api/...` GET endpoints
 * take a Scryfall query; the POST variants take the user's own pasted card
 * list (too large for a query string).
 *
 * Saving a list is account-bound: the `/api/lists` endpoints require a
 * logged-in Discord user and persist per-user via [CubeListService], so a
 * saved cube follows the user across devices. A logged-in user can also
 * publish an immutable shareable snapshot via [SharedCubeService]; anyone can
 * open it at `/magic/c/{token}` (no login).
 */
@Controller
@RequestMapping("/magic")
class CubeController(
    private val cubeWebService: CubeWebService,
    private val cubeLists: CubeListService,
    private val sharedCubes: SharedCubeService,
    private val priceWatches: CardPriceWatchService,
) {

    /**
     * The Magic command names for the page copy, sourced from [MtgCommandRef]
     * so the prose (e.g. "the website twin of `/card lookup`") can never drift
     * from the actual Discord commands. Available to this controller's views as
     * `${mtgCmd.cardLookup}` etc.
     */
    @ModelAttribute("mtgCmd")
    fun mtgCommands(): Map<String, String> = mapOf(
        "cardLookup" to MtgCommandRef.CARD_LOOKUP,
        "deckLegality" to MtgCommandRef.DECK_LEGALITY,
        "mtgSet" to MtgCommandRef.MTG_SET,
        "mtgRule" to MtgCommandRef.MTG_RULE,
        "pricewatchAdd" to MtgCommandRef.PRICEWATCH_ADD,
    )

    @GetMapping
    fun page(
        @AuthenticationPrincipal user: OAuth2User?,
        model: Model,
    ): String {
        model.addAttribute("username", user.displayName())
        // Drives the saved-lists UI: only logged-in users can save/load.
        model.addAttribute("loggedIn", user != null)
        return "magic"
    }

    /** Opens a shared cube: same page, with the list pre-loaded. */
    @GetMapping("/c/{token}")
    fun sharedPage(
        @PathVariable token: String,
        @AuthenticationPrincipal user: OAuth2User?,
        model: Model,
    ): String {
        model.addAttribute("username", user.displayName())
        model.addAttribute("loggedIn", user != null)
        val shared = sharedCubes.get(token)
        if (shared != null) {
            model.addAttribute("sharedName", shared.name)
            model.addAttribute("sharedCards", shared.cards)
        } else {
            model.addAttribute("sharedMissing", true)
        }
        return "magic"
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

    @GetMapping("/api/card", produces = ["application/json"])
    @ResponseBody
    fun card(@RequestParam("name") name: String): ResponseEntity<CubeCardResponse> =
        when (val result = cubeWebService.card(name)) {
            is CubeResult.Success -> ResponseEntity.ok(CubeCardResponse(true, null, result.value))
            is CubeResult.Failure -> ResponseEntity.badRequest().body(CubeCardResponse(false, result.error, null))
        }

    @GetMapping("/api/rulings", produces = ["application/json"])
    @ResponseBody
    fun rulings(@RequestParam("name") name: String): ResponseEntity<CubeRulingsResponse> =
        when (val result = cubeWebService.rulings(name)) {
            is CubeResult.Success -> ResponseEntity.ok(
                CubeRulingsResponse(true, null, result.value.name, result.value.scryfallUri, result.value.rulings)
            )
            is CubeResult.Failure ->
                ResponseEntity.badRequest().body(CubeRulingsResponse(false, result.error, null, null, emptyList()))
        }

    @GetMapping("/api/combos", produces = ["application/json"])
    @ResponseBody
    fun combos(@RequestParam("name") name: String): ResponseEntity<CubeCombosResponse> =
        when (val result = cubeWebService.combos(name)) {
            is CubeResult.Success -> ResponseEntity.ok(
                CubeCombosResponse(true, null, result.value.name, result.value.combos)
            )
            is CubeResult.Failure ->
                ResponseEntity.badRequest().body(CubeCombosResponse(false, result.error, null, emptyList()))
        }

    @GetMapping("/api/set", produces = ["application/json"])
    @ResponseBody
    fun set(@RequestParam("code") code: String): ResponseEntity<CubeSetResponse> =
        when (val result = cubeWebService.set(code)) {
            is CubeResult.Success -> ResponseEntity.ok(CubeSetResponse(true, null, result.value))
            is CubeResult.Failure -> ResponseEntity.badRequest().body(CubeSetResponse(false, result.error, null))
        }

    @GetMapping("/api/rule", produces = ["application/json"])
    @ResponseBody
    fun rule(@RequestParam("term") term: String): ResponseEntity<CubeRuleResponse> =
        when (val result = cubeWebService.rule(term)) {
            is CubeResult.Success -> ResponseEntity.ok(CubeRuleResponse(true, null, result.value))
            is CubeResult.Failure -> ResponseEntity.badRequest().body(CubeRuleResponse(false, result.error, null))
        }

    @PostMapping("/api/legality", consumes = ["application/json"], produces = ["application/json"])
    @ResponseBody
    fun legality(@RequestBody request: CubeLegalityRequest): ResponseEntity<CubeLegalityResponse> =
        when (val result = cubeWebService.checkLegality(request.list, request.format)) {
            is CubeResult.Success -> ResponseEntity.ok(
                CubeLegalityResponse(
                    ok = true, error = null,
                    format = result.value.format, legal = result.value.legal, total = result.value.total,
                    banned = result.value.banned, notLegal = result.value.notLegal,
                    restricted = result.value.restricted, unknown = result.value.unknown,
                    notFound = result.value.notFound, note = result.value.note,
                )
            )
            is CubeResult.Failure -> ResponseEntity.badRequest().body(
                CubeLegalityResponse(false, result.error, null, false, 0, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null)
            )
        }

    @PostMapping("/api/diff", consumes = ["application/json"], produces = ["application/json"])
    @ResponseBody
    fun diff(@RequestBody request: CubeDiffRequest): ResponseEntity<CubeDiffResponse> =
        when (val result = cubeWebService.diff(request.listA, request.listB)) {
            is CubeResult.Success -> ResponseEntity.ok(
                CubeDiffResponse(
                    true, null, result.value.added, result.value.removed, result.value.changed,
                    result.value.sizeA, result.value.sizeB,
                )
            )
            is CubeResult.Failure ->
                ResponseEntity.badRequest().body(CubeDiffResponse(false, result.error, emptyList(), emptyList(), emptyList(), 0, 0))
        }

    private fun previewResponse(result: CubeResult<PreviewData>): ResponseEntity<CubePreviewResponse> =
        when (result) {
            is CubeResult.Success -> ResponseEntity.ok(
                CubePreviewResponse(
                    true, null, result.value.poolSize, result.value.groups,
                    result.value.analytics, result.value.notFound, result.value.note,
                )
            )
            is CubeResult.Failure ->
                ResponseEntity.badRequest().body(CubePreviewResponse(false, result.error, 0, emptyList(), null, emptyList(), null))
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
                    note = result.value.note,
                )
            )
            is CubeResult.Failure -> ResponseEntity.badRequest().body(
                CubeGenerateResponse(false, result.error, 0, 0, 0, emptyList(), emptyList(), emptyList(), null)
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
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH ||
            request.cards.isBlank() || request.cards.length > MAX_CARDS_LENGTH
        ) {
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

    // --- Card price watches (account-bound; require a logged-in Discord user) ---

    @GetMapping("/api/watches", produces = ["application/json"])
    @ResponseBody
    fun watches(@AuthenticationPrincipal user: OAuth2User?): ResponseEntity<List<CardWatchView>> {
        val discordId = user?.discordIdOrNull() ?: return ResponseEntity.status(401).build()
        return ResponseEntity.ok(priceWatches.listForUser(discordId).map { it.toView() })
    }

    @PostMapping("/api/watches", consumes = ["application/json"], produces = ["application/json"])
    @ResponseBody
    fun addWatch(
        @RequestBody request: AddWatchRequest,
        @AuthenticationPrincipal user: OAuth2User?,
    ): ResponseEntity<AddWatchResponse> {
        val discordId = user?.discordIdOrNull() ?: return ResponseEntity.status(401).build()
        val currency = MtgCurrency.fromCode(request.currency) ?: MtgCurrency.DEFAULT
        val direction = runCatching { CardPriceWatchDto.Direction.valueOf(request.direction.trim().uppercase()) }
            .getOrNull() ?: return ResponseEntity.badRequest().body(AddWatchResponse(false, "Direction must be below or above.", null))
        if (request.threshold <= 0.0) {
            return ResponseEntity.badRequest().body(AddWatchResponse(false, "Target price must be positive.", null))
        }
        // Resolve the card (canonical name + current price) via Scryfall.
        val resolved = when (val r = cubeWebService.card(request.name)) {
            is CubeResult.Success -> r.value
            is CubeResult.Failure -> return ResponseEntity.badRequest().body(AddWatchResponse(false, r.error, null))
        }
        val current = when (currency) {
            MtgCurrency.USD -> resolved.priceUsd
            MtgCurrency.EUR -> resolved.priceEur
            MtgCurrency.TIX -> resolved.priceTix
        }?.toDoubleOrNull()
        // Web has no guild context — guild 0 routes the DM via the default opt-in.
        val created = priceWatches.create(discordId, 0L, resolved.name, currency.code, direction, request.threshold, current)
            ?: return ResponseEntity.status(409).body(AddWatchResponse(false, "You've hit the watch limit (${priceWatches.maxPerUser}).", null))
        return ResponseEntity.ok(AddWatchResponse(true, null, created.toView()))
    }

    @DeleteMapping("/api/watches")
    @ResponseBody
    fun deleteWatch(
        @RequestParam("id") id: Long,
        @AuthenticationPrincipal user: OAuth2User?,
    ): ResponseEntity<Void> {
        val discordId = user?.discordIdOrNull() ?: return ResponseEntity.status(401).build()
        return if (priceWatches.remove(id, discordId)) ResponseEntity.noContent().build()
        else ResponseEntity.notFound().build()
    }

    // --- Share links (logged-in user mints a public, immutable snapshot) ---

    @PostMapping("/api/share", consumes = ["application/json"], produces = ["application/json"])
    @ResponseBody
    fun share(
        @RequestBody request: ShareCubeRequest,
        @AuthenticationPrincipal user: OAuth2User?,
    ): ResponseEntity<ShareCubeResponse> {
        val discordId = user?.discordIdOrNull() ?: return ResponseEntity.status(401).build()
        if (request.cards.isBlank() || request.cards.length > MAX_CARDS_LENGTH) {
            return ResponseEntity.badRequest().build()
        }
        val name = request.name.trim().ifEmpty { "Shared cube" }.take(MAX_NAME_LENGTH)
        val row = sharedCubes.create(discordId, name, request.cards)
        return ResponseEntity.ok(ShareCubeResponse(token = row.token, url = "/magic/c/${row.token}", name = row.name))
    }

    private companion object {
        const val MAX_NAME_LENGTH = 100
        const val MAX_LISTS_PER_USER = 50

        // Generous ceiling on stored list text — comfortably fits a 750-card
        // cube with set tags, while bounding what an account can persist.
        const val MAX_CARDS_LENGTH = 100_000
    }
}

data class AddWatchRequest(
    val name: String = "",
    val currency: String = "usd",
    val direction: String = "below",
    val threshold: Double = 0.0,
)

data class AddWatchResponse(val ok: Boolean, val error: String?, val watch: CardWatchView?)

/** A card price watch as JSON for the web. */
data class CardWatchView(
    val id: Long,
    val cardName: String,
    val currency: String,
    val direction: String,
    val threshold: Double,
)

private fun CardPriceWatchDto.toView() = CardWatchView(
    id = id ?: 0,
    cardName = cardName,
    currency = currency,
    direction = direction.lowercase(),
    threshold = threshold,
)

data class ShareCubeRequest(val name: String = "", val cards: String = "")

data class ShareCubeResponse(val token: String, val url: String, val name: String)

data class CubeListPreviewRequest(val list: String = "", val packSize: Int = 15)

data class CubeCardResponse(val ok: Boolean, val error: String?, val card: CardLookupView?)

data class CubeRulingsResponse(
    val ok: Boolean,
    val error: String?,
    val name: String?,
    val scryfallUri: String?,
    val rulings: List<RulingView>,
)

data class CubeCombosResponse(
    val ok: Boolean,
    val error: String?,
    val name: String?,
    val combos: List<ComboView>,
)

data class CubeSetResponse(val ok: Boolean, val error: String?, val set: SetView?)

data class CubeRuleResponse(val ok: Boolean, val error: String?, val rule: RuleView?)

data class CubeLegalityRequest(val list: String = "", val format: String = "")

data class CubeLegalityResponse(
    val ok: Boolean,
    val error: String?,
    val format: String?,
    val legal: Boolean,
    val total: Int,
    val banned: List<String>,
    val notLegal: List<String>,
    val restricted: List<String>,
    val unknown: List<String>,
    val notFound: List<String>,
    val note: String?,
)

data class CubeDiffRequest(val listA: String = "", val listB: String = "")

data class CubeDiffResponse(
    val ok: Boolean,
    val error: String?,
    val added: List<DiffLineView>,
    val removed: List<DiffLineView>,
    val changed: List<DiffLineView>,
    val sizeA: Int,
    val sizeB: Int,
)

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
    val analytics: AnalyticsView? = null,
    val notFound: List<String> = emptyList(),
    val note: String? = null,
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
    val note: String? = null,
)
