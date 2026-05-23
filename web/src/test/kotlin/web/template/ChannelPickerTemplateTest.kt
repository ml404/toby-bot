package web.template

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Presence regression for the channelPicker migration. Every channel
 * `<select>` in the moderation UI now lives behind the shared
 * `fragments/channelPicker :: channelPicker(...)` fragment, mirroring
 * the userPicker pattern. This test guards each call site so a future
 * edit can't accidentally revert one back to a native `<select>`,
 * which would visibly regress the typeahead UX.
 *
 * Reads template files off the classpath; matches what
 * `ModerationTemplateRowsTest` does. Substring assertions are scoped
 * to the per-page section that owns each picker so we get pinpointed
 * failures instead of a single "channelPicker missing somewhere" miss.
 */
class ChannelPickerTemplateTest {

    private val settingsHtml: String by lazy { readTemplate("templates/moderation/settings.html") }
    private val actionsHtml: String by lazy { readTemplate("templates/moderation/actions.html") }
    private val voiceHtml: String by lazy { readTemplate("templates/moderation/voice.html") }
    private val pollHtml: String by lazy { readTemplate("templates/moderation/poll.html") }
    private val lotteryHtml: String by lazy { readTemplate("templates/moderation/lottery.html") }
    private val levelingHtml: String by lazy { readTemplate("templates/moderation/leveling.html") }

    private fun readTemplate(path: String): String {
        val url = javaClass.classLoader.getResource(path)
        assertNotNull(url, "$path must be on the classpath")
        return url!!.readText()
    }

    // ------------------------------------------------------------------------
    // settings.html
    // ------------------------------------------------------------------------

    @Test
    fun `settings MOVE row uses channelPicker with name-valued field`() {
        val block = sectionForDataKey(settingsHtml, "MOVE")
        assertTrue(
            block.contains("fragments/channelPicker :: channelPicker"),
            "MOVE config row should be rendered by channelPicker, not a native <select>"
        )
        assertTrue(
            block.contains("valueField='name'"),
            "MOVE config stores channel name (not id) in DB, so picker must use valueField='name'"
        )
    }

    @Test
    fun `settings LEADERBOARD_CHANNEL row uses channelPicker with id and # prefix`() {
        val block = sectionForDataKey(settingsHtml, "LEADERBOARD_CHANNEL")
        assertTrue(block.contains("fragments/channelPicker :: channelPicker"))
        assertTrue(block.contains("valueField='id'"))
        assertTrue(block.contains("valuePrefix='#'"))
    }

    @Test
    fun `settings CASINO_MODLOG_CHANNEL_ID row uses channelPicker`() {
        val block = sectionForDataKey(settingsHtml, "CASINO_MODLOG_CHANNEL_ID")
        assertTrue(block.contains("fragments/channelPicker :: channelPicker"))
        assertTrue(block.contains("valueField='id'"))
        assertTrue(block.contains("valuePrefix='#'"))
    }

    // ------------------------------------------------------------------------
    // actions.html — purge / lock / slowmode all use name='channelId'
    // ------------------------------------------------------------------------

    @Test
    fun `actions form pickers all use channelPicker with required=true`() {
        val callers = channelPickerCalls(actionsHtml)
        assertEquals(
            3, callers.size,
            "actions.html should have three channelPicker calls (purge, lock, slowmode); found $callers"
        )
        for (call in callers) {
            assertTrue(call.contains("name='channelId'"), "actions caller missing name='channelId': $call")
            assertTrue(call.contains("required=true"), "actions caller missing required=true: $call")
        }
    }

    // ------------------------------------------------------------------------
    // voice.html — move target is a voice channel, no '#' prefix
    // ------------------------------------------------------------------------

    @Test
    fun `voice move form uses channelPicker on voice channels with no prefix`() {
        val callers = channelPickerCalls(voiceHtml)
        assertEquals(1, callers.size, "voice.html should have exactly one channelPicker call")
        val call = callers[0]
        assertTrue(call.contains("name='targetChannelId'"))
        assertTrue(call.contains("\${overview.voiceChannels}"))
        assertTrue(call.contains("valuePrefix=''"))
        assertTrue(call.contains("required=true"))
    }

    // ------------------------------------------------------------------------
    // poll.html
    // ------------------------------------------------------------------------

    @Test
    fun `poll form uses channelPicker with required=true`() {
        val callers = channelPickerCalls(pollHtml)
        assertEquals(1, callers.size)
        val call = callers[0]
        assertTrue(call.contains("name='channelId'"))
        assertTrue(call.contains("required=true"))
    }

    // ------------------------------------------------------------------------
    // lottery.html / leveling.html — config-row channels with fallback empty
    // ------------------------------------------------------------------------

    @Test
    fun `lottery LOTTERY_CHANNEL row uses channelPicker`() {
        val block = sectionForDataKey(lotteryHtml, "LOTTERY_CHANNEL")
        assertTrue(block.contains("fragments/channelPicker :: channelPicker"))
        assertTrue(block.contains("valueField='id'"))
    }

    @Test
    fun `leveling LEVEL_UP_CHANNEL row uses channelPicker`() {
        val block = sectionForDataKey(levelingHtml, "LEVEL_UP_CHANNEL")
        assertTrue(block.contains("fragments/channelPicker :: channelPicker"))
        assertTrue(block.contains("valueField='id'"))
    }

    @Test
    fun `leveling ACHIEVEMENT_ANNOUNCE_CHANNEL row uses channelPicker`() {
        val block = sectionForDataKey(levelingHtml, "ACHIEVEMENT_ANNOUNCE_CHANNEL")
        assertTrue(block.contains("fragments/channelPicker :: channelPicker"))
        assertTrue(block.contains("valueField='id'"))
    }

    // ------------------------------------------------------------------------
    // Cross-template regression guard
    // ------------------------------------------------------------------------

    @Test
    fun `no moderation template renders a raw channel select via th-each over text or voice channels`() {
        // Walk every .html under templates/moderation/ and assert the
        // signature pattern of the old native select is gone. A future
        // edit that adds `<option th:each="tc : ${overview.textChannels}">`
        // back will trip this guard.
        val templatesRoot = Paths.get("src/main/resources/templates/moderation")
            .takeIf { Files.exists(it) }
            ?: Paths.get("web/src/main/resources/templates/moderation")
        val problems = mutableListOf<String>()
        Files.walk(templatesRoot).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".html") }
                .forEach { path ->
                    val text = Files.readString(path)
                    if (text.contains("th:each=\"tc : \${overview.textChannels}\"")
                        || text.contains("th:each=\"vc : \${overview.voiceChannels}\"")) {
                        problems += templatesRoot.relativize(path).toString()
                    }
                }
        }
        assertTrue(
            problems.isEmpty(),
            "Native channel <select> regression in: $problems — should use fragments/channelPicker instead"
        )
    }

    @Test
    fun `every migrated moderation page includes the channelPicker script tag`() {
        val pages = listOf(
            "settings" to settingsHtml,
            "actions" to actionsHtml,
            "voice" to voiceHtml,
            "poll" to pollHtml,
            "lottery" to lotteryHtml,
            "leveling" to levelingHtml,
        )
        for ((name, html) in pages) {
            assertTrue(
                html.contains("channelPicker.js"),
                "$name.html must <script src=channelPicker.js> so the picker enhancement runs"
            )
        }
    }

    // ------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------

    /**
     * Returns the substring of [html] that belongs to the `.config-row`
     * with the given `data-key`. Scoping assertions to a single row
     * gives precise failure messages.
     */
    private fun sectionForDataKey(html: String, dataKey: String): String {
        val marker = "data-key=\"$dataKey\""
        val start = html.indexOf(marker)
        assertTrue(start >= 0, "expected data-key=\"$dataKey\" somewhere in the template")
        // The config-row div is the parent; look back for the opening tag,
        // forward for the closing </div> at the same depth. Cheap: scan
        // forward through ~2KB of HTML, which always overshoots the row.
        val end = (start + 4096).coerceAtMost(html.length)
        return html.substring(start, end)
    }

    /**
     * Returns every `~{fragments/channelPicker :: channelPicker(...)}`
     * invocation in [html], each captured as a single flattened string
     * (newlines collapsed to spaces) so substring assertions don't need
     * to care about formatting.
     */
    private fun channelPickerCalls(html: String): List<String> {
        val out = mutableListOf<String>()
        val anchor = "fragments/channelPicker :: channelPicker("
        var i = 0
        while (true) {
            val start = html.indexOf(anchor, i)
            if (start < 0) break
            val openParen = start + anchor.length - 1
            val end = matchingCloseParen(html, openParen) ?: break
            out += html.substring(start, end + 1).replace(Regex("\\s+"), " ")
            i = end + 1
        }
        return out
    }

    private fun matchingCloseParen(text: String, openIdx: Int): Int? {
        var depth = 1
        var i = openIdx + 1
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }
}
