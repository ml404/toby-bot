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
 * Summaries are lifted verbatim from PR bodies by the Action, so they
 * arrive carrying markdown (`**bold**`, `### headings`, backticks) and
 * a hard 480-char cut that usually lands mid-sentence. The template
 * escapes rather than renders markdown, so that syntax would show up
 * as literal punctuation on a public page. [tidySummary] strips it and
 * trims back to the last whole sentence at load time — see that
 * function for the exact rules. Entries are then grouped by [date] so
 * the page reads as a run of months rather than one flat list of
 * near-identical rows.
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
        .map { entry -> entry.copy(summary = tidySummary(entry.summary, entry.title)) }

    private val months: List<ChangelogMonth> = groupByMonth(entries)

    @GetMapping("/changelog")
    fun changelog(
        @AuthenticationPrincipal user: OAuth2User?,
        model: Model,
    ): String {
        model.addAttribute("entries", entries)
        model.addAttribute("months", months)
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
     * @param summary 1–2 sentences. Plain text, no markdown. Whatever
     *                the Action wrote is passed through [tidySummary]
     *                before it reaches the template, so the rendered
     *                value can be shorter than the stored one — and is
     *                blank when nothing publishable survived.
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

    /**
     * All the entries sharing one [date], rendered under a single
     * sticky month heading.
     *
     * @param date the shared, already-trimmed date string.
     * @param entries that month's entries, newest first.
     */
    data class ChangelogMonth(
        val date: String,
        val entries: List<ChangelogEntry>,
    ) {
        /**
         * Heading text. Falls back to "Earlier" for the (unexpected)
         * case of an entry with a blank date, so the group still gets a
         * visible header rather than an empty bar.
         */
        val label: String get() = date.ifBlank { UNDATED_LABEL }

        /** `id` for the heading, linked from the month index at the top of the page. */
        val anchor: String get() = ANCHOR_UNSAFE.replace(date.lowercase(), "-")
            .trim('-')
            .ifBlank { UNDATED_ANCHOR }

        /** Entry count, shown alongside the heading. */
        val count: Int get() = entries.size
    }

    /** Number of entries currently loaded. Exposed for tests. */
    fun entryCount(): Int = entries.size

    /** Month groupings currently loaded. Exposed for tests. */
    fun monthCount(): Int = months.size

    companion object {
        const val CHANGELOG_RESOURCE = "changelog.json"

        /**
         * Longest summary the page renders. The Action caps its own
         * extraction at 480 chars, which reads as a wall of prose next
         * to 80-odd sibling entries; this trims back to the last whole
         * sentence that fits, and the PR link carries the full detail.
         */
        const val SUMMARY_MAX_CHARS = 260

        /**
         * Below this a "summary" is a stray section heading the Action
         * scraped off a PR body ("## Why") rather than prose. Those
         * render as nothing at all — the title already says more.
         */
        const val SUMMARY_MIN_CHARS = 16

        private const val UNDATED_LABEL = "Earlier"
        private const val UNDATED_ANCHOR = "earlier"

        private val ANCHOR_UNSAFE = Regex("[^a-z0-9]+")

        // Inline HTML the Action copied out of a PR body. Bounded length
        // so a bare "<" in prose ("value < 10, see below") can't swallow
        // the rest of the sentence.
        private val HTML_TAG = Regex("<[^<>]{1,80}>")

        private val MD_LINK = Regex("""\[([^\[\]]+)]\([^()\s]*\)""")

        // Heading markers only ever run "### " — the trailing whitespace
        // requirement keeps PR references like "#520" intact.
        private val MD_HEADING = Regex("""#{1,6}\s+""")

        // Paired emphasis runs. Single * and _ are left alone: underscores
        // show up inside the identifiers these summaries quote
        // (PENDING_HIDDEN_CODES), and stripping them would corrupt names.
        private val MD_EMPHASIS = Regex("""\*\*|__|~~""")

        private val WHITESPACE = Regex("""\s+""")

        private const val SENTENCE_ENDINGS = ".!?"

        // Abbreviations that end in a period but never end a sentence.
        // "etc." is deliberately absent — it often does close one, and
        // skipping it would cut the summary shorter than it needs to be.
        private val ABBREVIATIONS = listOf("e.g.", "i.e.", "cf.", "vs.", "approx.", "resp.")

        /**
         * Turn one raw PR-body scrape into a sentence a visitor can read.
         *
         * Strips inline HTML, markdown links, heading markers, paired
         * emphasis and code backticks; collapses the whitespace the
         * Action's line-joining leaves behind; then bounds the length —
         * cutting back to the last complete sentence that fits, or to a
         * word boundary with an ellipsis when there is no sentence break
         * to land on.
         *
         * A summary the Action already truncated (it ends in "…") gets
         * its dangling half-sentence dropped even when it is under the
         * cap, since that fragment is never a whole thought. Summaries
         * that arrive short and clean are returned untouched.
         *
         * Returns "" when nothing publishable survives — too short to be
         * prose (see [SUMMARY_MIN_CHARS]), or just the [title] again,
         * which the Action falls back to for PRs with an empty body. The
         * page prints the title directly above, so repeating it there
         * only costs the reader a line.
         */
        fun tidySummary(raw: String, title: String = ""): String {
            val wasTruncated = raw.trimEnd().let { it.endsWith('…') || it.endsWith("...") }
            val cleaned = raw
                .replace(HTML_TAG, " ")
                .replace(MD_LINK, "$1")
                .replace(MD_HEADING, "")
                .replace(MD_EMPHASIS, "")
                .replace("`", "")
                .replace("…", " ")
                .replace(WHITESPACE, " ")
                .trim()

            if (cleaned.length < SUMMARY_MIN_CHARS) return ""
            if (cleaned.equals(title.trim(), ignoreCase = true)) return ""
            return clamp(cleaned, wasTruncated)
        }

        private fun clamp(text: String, wasTruncated: Boolean): String {
            val overLong = text.length > SUMMARY_MAX_CHARS
            if (!overLong && !wasTruncated) return text

            val window = if (overLong) text.take(SUMMARY_MAX_CHARS) else text
            val sentenceEnd = lastSentenceEnd(window)
            if (sentenceEnd >= SUMMARY_MIN_CHARS) return window.substring(0, sentenceEnd + 1)

            // Nothing sentence-shaped to cut back to: keep as much as fits
            // and mark it as clipped.
            val lastSpace = window.lastIndexOf(' ')
            val head = if (lastSpace >= SUMMARY_MIN_CHARS) window.take(lastSpace) else window
            return head.trimEnd(' ', ',', ';', ':', '-', '—') + "…"
        }

        /**
         * Index of the last `.`/`!`/`?` that closes a sentence.
         *
         * Three tests, all cheap and all earning their keep on real PR
         * prose: the terminator must be followed by a space (so "v1.2"
         * and "home.css" don't read as breaks); the next word must not
         * start lower-case (so "…punishing that (e.g. making one-ticket
         * entries optimal)" doesn't break at "e.g."); and the
         * terminator must not close a known [ABBREVIATIONS] entry (so
         * "…an empty bar (e.g. 0/100)" doesn't either, where the digit
         * passes the previous test). A sentence opening on "(", a digit
         * or a capitalised identifier all still count.
         */
        private fun lastSentenceEnd(text: String): Int {
            for (i in text.indices.reversed()) {
                if (text[i] !in SENTENCE_ENDINGS) continue
                if (isAbbreviation(text, i)) continue
                if (i == text.lastIndex) return i
                if (text[i + 1] != ' ') continue
                val next = text.getOrNull(i + 2)
                if (next == null || !next.isLowerCase()) return i
            }
            return -1
        }

        /** Whether the terminator at [end] closes an [ABBREVIATIONS] entry. */
        private fun isAbbreviation(text: String, end: Int): Boolean =
            ABBREVIATIONS.any { abbreviation ->
                text.regionMatches(
                    thisOffset = end - abbreviation.length + 1,
                    other = abbreviation,
                    otherOffset = 0,
                    length = abbreviation.length,
                    ignoreCase = true,
                )
            }

        /**
         * Bucket entries under their [ChangelogEntry.date]. `groupBy`
         * keeps encounter order, so months come out newest-first exactly
         * as the JSON array is ordered, and entries keep their order
         * within a month.
         */
        fun groupByMonth(entries: List<ChangelogEntry>): List<ChangelogMonth> =
            entries.groupBy { it.date.trim() }
                .map { (date, monthEntries) -> ChangelogMonth(date, monthEntries) }
    }
}
