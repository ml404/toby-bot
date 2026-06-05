package web.controller

import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import web.service.CategoryAsFan
import web.service.CubeResult
import web.service.CubeWebService
import web.util.displayName

/**
 * Public web surface for the MTG cube tool — the same as-fan maths and
 * pack generation as the `/cube` Discord command, no login required (like
 * `/dnd` and `/utils`). GET renders the page; the `/api/...` GET endpoints
 * return JSON the page's JS renders.
 */
@Controller
@RequestMapping("/cube")
class CubeController(
    private val cubeWebService: CubeWebService,
) {

    @GetMapping
    fun page(
        @AuthenticationPrincipal user: OAuth2User?,
        model: Model,
    ): String {
        model.addAttribute("username", user.displayName())
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
    ): ResponseEntity<CubePreviewResponse> =
        when (val result = cubeWebService.preview(query, packSize)) {
            is CubeResult.Success -> ResponseEntity.ok(
                CubePreviewResponse(true, null, result.value.poolSize, result.value.distribution)
            )
            is CubeResult.Failure -> ResponseEntity.badRequest().body(CubePreviewResponse(false, result.error, 0, emptyList()))
        }

    @GetMapping("/api/generate", produces = ["application/json"])
    @ResponseBody
    fun generate(
        @RequestParam("query") query: String,
        @RequestParam("packs", defaultValue = "24") packs: Int,
        @RequestParam("packSize", defaultValue = "15") packSize: Int,
        @RequestParam("balanced", defaultValue = "true") balanced: Boolean,
    ): ResponseEntity<CubeGenerateResponse> =
        when (val result = cubeWebService.generate(query, packs, packSize, balanced)) {
            is CubeResult.Success -> ResponseEntity.ok(
                CubeGenerateResponse(
                    ok = true,
                    error = null,
                    poolSize = result.value.poolSize,
                    packCount = result.value.packCount,
                    packSize = result.value.packSize,
                    packs = result.value.packs,
                    distribution = result.value.distribution,
                )
            )
            is CubeResult.Failure -> ResponseEntity.badRequest().body(
                CubeGenerateResponse(false, result.error, 0, 0, 0, emptyList(), emptyList())
            )
        }
}

data class CubeAsFanResponse(val ok: Boolean, val error: String?, val value: Double?)

data class CubePreviewResponse(
    val ok: Boolean,
    val error: String?,
    val poolSize: Int,
    val distribution: List<CategoryAsFan>,
)

data class CubeGenerateResponse(
    val ok: Boolean,
    val error: String?,
    val poolSize: Int,
    val packCount: Int,
    val packSize: Int,
    val packs: List<List<String>>,
    val distribution: List<CategoryAsFan>,
)
