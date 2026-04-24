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
import web.service.MemeResult
import web.service.UtilsWebService
import web.util.displayName

@Controller
@RequestMapping("/utils")
class UtilsController(
    private val utilsWebService: UtilsWebService
) {

    @GetMapping
    fun page(
        @AuthenticationPrincipal user: OAuth2User?,
        model: Model
    ): String {
        model.addAttribute("username", user.displayName())
        return "utils"
    }

    @GetMapping("/api/meme", produces = ["application/json"])
    @ResponseBody
    fun meme(
        @RequestParam("subreddit", defaultValue = "") subreddit: String,
        @RequestParam("timePeriod", defaultValue = "day") timePeriod: String,
        @RequestParam("limit", defaultValue = "10") limit: Int
    ): ResponseEntity<MemeResponse> {
        val result = utilsWebService.randomMeme(subreddit, timePeriod, limit)
        return if (result.error != null || result.value == null) {
            ResponseEntity.badRequest().body(MemeResponse(false, result.error, null))
        } else {
            ResponseEntity.ok(MemeResponse(true, null, result.value))
        }
    }

    @GetMapping("/api/dbd-killer", produces = ["application/json"])
    @ResponseBody
    fun dbdKiller(): ResponseEntity<TextResponse> {
        val result = utilsWebService.randomDbdKiller()
        return if (result.error != null || result.value == null) {
            ResponseEntity.badRequest().body(TextResponse(false, result.error, null))
        } else {
            ResponseEntity.ok(TextResponse(true, null, result.value))
        }
    }

    @GetMapping("/api/kf2-map", produces = ["application/json"])
    @ResponseBody
    fun kf2Map(): ResponseEntity<TextResponse> {
        val result = utilsWebService.randomKf2Map()
        return if (result.error != null || result.value == null) {
            ResponseEntity.badRequest().body(TextResponse(false, result.error, null))
        } else {
            ResponseEntity.ok(TextResponse(true, null, result.value))
        }
    }
}

data class MemeResponse(val ok: Boolean, val error: String?, val meme: MemeResult?)
data class TextResponse(val ok: Boolean, val error: String?, val text: String?)
