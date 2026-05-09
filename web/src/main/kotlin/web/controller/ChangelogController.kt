package web.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import web.util.displayName

/**
 * Public changelog page. Entries live in `resources/changelog.json` so a
 * GitHub Action (`.github/workflows/changelog.yml`) can prepend a new
 * entry on every merged PR without touching Kotlin. The Action picks
 * the emoji from the PR's feature labels (casino → 🎰, moderation →
 * 🛡️, etc.) and skips PRs labelled `chore`, `docs`, `ci`, `build`,
 * `style`, `dependencies`, or `skip-changelog`.
 *
 * The list is loaded once at controller construction. If the JSON is
 * missing or malformed the page renders empty rather than 500-ing —
 * losing a changelog entry is annoying but not page-fatal.
 *
 * Public endpoint (no auth) so recruiters and prospective installers
 * can scan recent activity without signing in. Route is permitAll-listed
 * in [web.configuration.WebSecurityConfig].
 */
@Controller
class ChangelogController(
    objectMapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val entries: List<ChangelogEntry> = loadEntries(objectMapper)

    @GetMapping("/changelog")
    fun changelog(
        @AuthenticationPrincipal user: OAuth2User?,
        model: Model,
    ): String {
        model.addAttribute("entries", entries)
        if (user != null) model.addAttribute("username", user.displayName())
        return "changelog"
    }

    private fun loadEntries(objectMapper: ObjectMapper): List<ChangelogEntry> {
        val resource = ClassPathResource(CHANGELOG_RESOURCE)
        if (!resource.exists()) {
            log.warn("changelog.json not found on classpath; serving empty changelog page")
            return emptyList()
        }
        return runCatching {
            resource.inputStream.use { stream ->
                objectMapper.readValue<List<ChangelogEntry>>(stream)
            }
        }.onFailure { e ->
            log.error("Failed to parse changelog.json; serving empty changelog page", e)
        }.getOrDefault(emptyList())
    }

    /**
     * One row in the changelog timeline.
     *
     * @param date  human-readable, e.g. "May 2026". Sort order is the
     *              position in the JSON array — newest first.
     * @param title short headline.
     * @param summary 1–2 sentences. Plain text, no markdown.
     * @param emoji optional leading glyph; null hides the icon column.
     * @param prNumber optional GitHub PR number; renders as a small link.
     */
    data class ChangelogEntry(
        val date: String = "",
        val title: String = "",
        val summary: String = "",
        val emoji: String? = null,
        val prNumber: Int? = null,
    )

    /** Number of entries currently loaded. Exposed for tests. */
    fun entryCount(): Int = entries.size

    companion object {
        const val CHANGELOG_RESOURCE = "changelog.json"
    }
}
