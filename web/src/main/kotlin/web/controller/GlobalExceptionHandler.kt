package web.controller

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import web.util.DefaultGuildCookie
import java.net.URI

@ControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleMaxUploadSize(
        request: HttpServletRequest,
        ra: RedirectAttributes
    ): String {
        ra.addFlashAttribute("error", "File too large. Maximum size is 550KB.")
        return "redirect:${safeRefererTarget(request.getHeader("Referer"))}"
    }

    companion object {
        private const val FALLBACK = "/intro/guilds"

        /**
         * The Referer header is attacker-controllable, so it must never feed
         * a redirect verbatim (open redirect → phishing). Keep only the path
         * and query of the referring page and pass the path through the same
         * in-app whitelist the cookie redirects use; anything that doesn't
         * survive as a clean single-slash relative path falls back to the
         * intro picker.
         */
        internal fun safeRefererTarget(referer: String?): String {
            val uri = referer?.let { runCatching { URI(it) }.getOrNull() }
            val path = DefaultGuildCookie.sanitizeRedirect(uri?.rawPath, fallback = FALLBACK)
            if (path == FALLBACK) return FALLBACK
            val query = uri?.rawQuery
            return if (query.isNullOrBlank()) path else "$path?$query"
        }
    }
}
