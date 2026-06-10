package web.activity

import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseBody

/**
 * Entry point for the Discord Activity surface.
 *
 * GET /activity renders the bootstrap shell that Discord loads in the
 * Activity iframe (via the `*.discordsays.com` proxy). The shell runs the
 * Embedded App SDK handshake — ready → authorize → POST the code here →
 * authenticate — then navigates into the existing casino pages with the
 * issued session token (see activity.js).
 *
 * POST /activity/api/token is the code-for-session exchange. Anonymous by
 * design (the caller can't be authenticated yet) and CSRF-exempt: it sets
 * no cookie, mutates nothing besides minting a token for the code's owner,
 * and the code itself is single-use and Discord-issued.
 */
@Controller
class ActivityController(
    private val sessions: ActivitySessions,
    @param:Value($$"${spring.security.oauth2.client.registration.discord.client-id:}") private val clientId: String,
) {

    @GetMapping("/activity")
    fun shell(model: Model): String {
        model.addAttribute("clientId", clientId.trim())
        return "activity"
    }

    @PostMapping("/activity/api/token")
    @ResponseBody
    fun token(
        @RequestBody request: ActivityTokenRequest,
        response: HttpServletResponse,
    ): ResponseEntity<ActivityTokenResponse> {
        val code = request.code?.takeIf { it.isNotBlank() }
            ?: return ResponseEntity.badRequest()
                .body(ActivityTokenResponse(ok = false, error = "Missing authorization code."))
        val issued = sessions.exchange(code)
            ?: return ResponseEntity.status(502)
                .body(ActivityTokenResponse(ok = false, error = "Discord sign-in failed — relaunch the activity."))
        // Belt-and-braces session carrier: the shell threads the token
        // into navigations as ?activityToken=, but if a proxy hop or
        // client webview drops query params, this cookie keeps page
        // GETs (and EventSource) authenticated. SameSite=None because
        // the page lives in the Discord iframe (a third-party context);
        // the cookie is scoped to the proxied origin and HttpOnly.
        // CSRF stays enforced for cookie-carried requests (see
        // WebSecurityConfig), so SameSite=None doesn't widen mutation
        // exposure.
        response.addHeader(
            "Set-Cookie",
            "${ActivityTokenAuthFilter.COOKIE_NAME}=${issued.sessionToken}; " +
                "Path=/; Max-Age=$COOKIE_MAX_AGE_SECONDS; Secure; HttpOnly; SameSite=None"
        )
        return ResponseEntity.ok(
            ActivityTokenResponse(ok = true, sessionToken = issued.sessionToken, accessToken = issued.accessToken)
        )
    }

    companion object {
        /** Matches the session store's 12h cap in [ActivitySessionService]. */
        private const val COOKIE_MAX_AGE_SECONDS = 12 * 60 * 60
    }
}

data class ActivityTokenRequest(val code: String? = null)

data class ActivityTokenResponse(
    val ok: Boolean,
    val error: String? = null,
    val sessionToken: String? = null,
    /** Returned because the SDK's authenticate() command needs the raw Discord token client-side. */
    val accessToken: String? = null,
)
