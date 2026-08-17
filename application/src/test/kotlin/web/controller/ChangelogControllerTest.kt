package web.controller

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.ui.Model

class ChangelogControllerTest {

    // Plain Jackson ObjectMapper — no jackson-module-kotlin import. The
    // kotlin module is on the runtime classpath (Spring Boot
    // auto-registers it on the wired-in ObjectMapper) but not on the
    // :application module's *test compile* classpath. ChangelogEntry's
    // data class has defaults on every field so the synthetic no-arg
    // constructor is enough for Jackson to deserialize without the
    // kotlin module.
    private val objectMapper: ObjectMapper = ObjectMapper()

    private lateinit var controller: ChangelogController
    private lateinit var model: Model

    @BeforeEach
    fun setup() {
        controller = ChangelogController(objectMapper)
        model = mockk(relaxed = true)
    }

    @Test
    fun `changelog returns changelog view`() {
        val view = controller.changelog(null, model)

        assertEquals("changelog", view)
    }

    @Test
    fun `changelog adds the loaded entries onto the model`() {
        controller.changelog(null, model)

        verify { model.addAttribute("entries", any<List<ChangelogController.ChangelogEntry>>()) }
    }

    @Test
    fun `controller loads at least one entry from the classpath JSON`() {
        // Sanity check: if changelog.json is missing or malformed the
        // controller falls back to empty silently — guard against that
        // shipping unnoticed.
        assertTrue(
            controller.entryCount() > 0,
            "ChangelogController should load entries from ${ChangelogController.CHANGELOG_RESOURCE} on the classpath"
        )
    }

    @Test
    fun `changelog json entries have valid shape`() {
        // Read the resource directly (independent of the controller) so a
        // future schema drift surfaces here, not at template-render time.
        val url = javaClass.classLoader.getResource(ChangelogController.CHANGELOG_RESOURCE)
        assertNotNull(url, "${ChangelogController.CHANGELOG_RESOURCE} must be on the test classpath")
        val entries: List<ChangelogController.ChangelogEntry> = objectMapper.readValue(
            url!!.openStream(),
            object : TypeReference<List<ChangelogController.ChangelogEntry>>() {}
        )
        assertTrue(entries.isNotEmpty(), "changelog.json should seed at least one entry")
        val first = entries.first()
        assertTrue(first.date.isNotBlank(), "first entry should have a date")
        assertTrue(first.title.isNotBlank(), "first entry should have a title")
        assertTrue(first.summary.isNotBlank(), "first entry should have a summary")
    }

    @Test
    fun `changelog adds the month groupings onto the model`() {
        controller.changelog(null, model)

        verify { model.addAttribute("months", any<List<ChangelogController.ChangelogMonth>>()) }
    }

    @Test
    fun `grouping keeps every entry, newest month first`() {
        val entries = listOf(
            entry(date = "May 2026", title = "Newest"),
            entry(date = "May 2026", title = "Also May"),
            entry(date = "April 2026", title = "Older"),
        )

        val months = ChangelogController.groupByMonth(entries)

        assertEquals(listOf("May 2026", "April 2026"), months.map { it.date })
        assertEquals(listOf(2, 1), months.map { it.count })
        assertEquals(entries.size, months.sumOf { it.entries.size })
        assertEquals(listOf("Newest", "Also May"), months.first().entries.map { it.title })
    }

    @Test
    fun `the shipped entries all land in a month group`() {
        assertTrue(controller.monthCount() > 0, "entries should group into at least one month")
        assertTrue(
            controller.monthCount() <= controller.entryCount(),
            "grouping should never invent months"
        )
    }

    @Test
    fun `month label and anchor fall back for a blank date`() {
        val undated = ChangelogController.ChangelogMonth("", emptyList())

        assertEquals("Earlier", undated.label)
        assertEquals("earlier", undated.anchor)
    }

    @Test
    fun `month anchor slugifies the date`() {
        val month = ChangelogController.ChangelogMonth("May 2026", emptyList())

        assertEquals("may-2026", month.anchor)
        assertEquals("May 2026", month.label)
    }

    @Test
    fun `tidySummary strips markdown the page would otherwise print literally`() {
        val tidied = ChangelogController.tidySummary(
            "### What changed **13 new event classes** under `common.events` — one per game."
        )

        assertEquals("What changed 13 new event classes under common.events — one per game.", tidied)
    }

    @Test
    fun `tidySummary keeps PR references intact`() {
        val tidied = ChangelogController.tidySummary("Follow-up to #520. That PR reserved the stubs.")

        assertTrue(tidied.contains("#520"), "expected '#520' to survive heading stripping, got: $tidied")
    }

    @Test
    fun `tidySummary keeps underscores inside identifiers`() {
        val tidied = ChangelogController.tidySummary("Wires them up so PENDING_HIDDEN_CODES is empty.")

        assertEquals("Wires them up so PENDING_HIDDEN_CODES is empty.", tidied)
    }

    @Test
    fun `tidySummary drops a dangling half sentence left by the truncating Action`() {
        val tidied = ChangelogController.tidySummary(
            "Adds the market page. Prices tick every five minutes. Each tick scans enabled trig…"
        )

        assertEquals("Adds the market page. Prices tick every five minutes.", tidied)
    }

    @Test
    fun `tidySummary trims an over-long summary back to a whole sentence`() {
        val sentence = "Every tick scans the enabled price triggers and fires the ones that match. "
        val tidied = ChangelogController.tidySummary(sentence.repeat(6))

        assertTrue(
            tidied.length <= ChangelogController.SUMMARY_MAX_CHARS,
            "expected at most ${ChangelogController.SUMMARY_MAX_CHARS} chars, got ${tidied.length}"
        )
        assertTrue(tidied.endsWith("match."), "expected a clean sentence ending, got: $tidied")
    }

    @Test
    fun `tidySummary does not mistake an abbreviation for the end of a sentence`() {
        val filler = "Some users had figured out that buying a single ticket was enough to be entered. "
        val tidied = ChangelogController.tidySummary(
            filler.repeat(2) + "Making one-ticket entries the optimal play. " +
                "Rather than punishing that (e.g. by raising the minimum) the fix rewards volume instead."
        )

        assertTrue(
            !tidied.endsWith("(e.g."),
            "expected the trim to skip past the 'e.g.' abbreviation, got: $tidied"
        )
        assertTrue(tidied.endsWith("play."), "expected a clean sentence ending, got: $tidied")
    }

    @Test
    fun `tidySummary does not break on an abbreviation followed by a number`() {
        // Sized so the "e.g." lands inside the 260-char window and the
        // real sentence end after it falls outside — without the guard
        // the trim would stop at the abbreviation.
        val filler = "The progress field on the level-up embed was computed from the level curve. "
        val tidied = ChangelogController.tidySummary(
            filler.repeat(2) +
                "That made the celebration render an empty bar (e.g. 0/100) on every " +
                "level-up for every guild that had the feature switched on."
        )

        assertTrue(
            !tidied.endsWith("(e.g."),
            "expected the digit after 'e.g.' not to read as a new sentence, got: $tidied"
        )
        assertTrue(tidied.endsWith("curve."), "expected a clean sentence ending, got: $tidied")
    }

    @Test
    fun `tidySummary leaves a short clean summary alone`() {
        val clean = "Per-server live market with a 5-minute price tick"

        assertEquals(clean, ChangelogController.tidySummary(clean))
    }

    @Test
    fun `tidySummary drops a stray section heading`() {
        assertEquals("", ChangelogController.tidySummary("## Why"))
    }

    @Test
    fun `tidySummary drops a summary that only repeats the title`() {
        val title = "Web buy/sell watches on the market page"

        assertEquals("", ChangelogController.tidySummary(title, title))
    }

    @Test
    fun `every rendered summary is free of markdown and within the cap`() {
        // Guards the whole shipped file, not just the hand-written cases
        // above: nothing the Action has already written should reach the
        // page carrying syntax or overflowing the cap.
        loadRawEntries().forEach { entry ->
            val summary = ChangelogController.tidySummary(entry.summary, entry.title)
            assertTrue(
                summary.length <= ChangelogController.SUMMARY_MAX_CHARS,
                "summary for #${entry.prNumber} is ${summary.length} chars: $summary"
            )
            listOf("**", "`", "## ", "### ").forEach { syntax ->
                assertTrue(
                    !summary.contains(syntax),
                    "summary for #${entry.prNumber} still contains '$syntax': $summary"
                )
            }
        }
    }

    private fun entry(
        date: String = "May 2026",
        title: String = "Headline",
        summary: String = "Summary.",
    ) = ChangelogController.ChangelogEntry(date = date, title = title, summary = summary)

    private fun loadRawEntries(): List<ChangelogController.ChangelogEntry> {
        val url = javaClass.classLoader.getResource(ChangelogController.CHANGELOG_RESOURCE)
        assertNotNull(url, "${ChangelogController.CHANGELOG_RESOURCE} must be on the test classpath")
        return objectMapper.readValue(
            url!!.openStream(),
            object : TypeReference<List<ChangelogController.ChangelogEntry>>() {}
        )
    }

    @Test
    fun `changelog skips username when user is not authenticated`() {
        controller.changelog(null, model)

        verify(exactly = 0) { model.addAttribute("username", any()) }
    }

    @Test
    fun `changelog adds username when user is authenticated`() {
        val user = mockk<OAuth2User>(relaxed = true)
        every { user.getAttribute<String>("username") } returns "TestUser"

        controller.changelog(user, model)

        verify { model.addAttribute("username", "TestUser") }
    }
}
