package web.template

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Text-based scanner that asserts every Thymeleaf fragment caller passes
 * the expected number of positional arguments. Catches the class of bug
 * that took down the casino pages in production: `fragments/head` was
 * expanded from `head(pageTitle, extraCss)` to
 * `head(pageTitle, extraCss, extraCss2)`, and Thymeleaf 3.1 throws a
 * rendering exception when a 2-arg caller can't bind to the new
 * 3-param signature — every page that didn't pass the third arg
 * returned a 500 with a blank body.
 *
 * Existing controller tests all mock the service layer and assert on
 * JSON responses; none of them actually render templates, so a
 * fragment-signature mismatch breezes past them. This test fills that
 * gap by parsing the raw template files and matching every
 * `~{path :: name(args)}` call against the corresponding `th:fragment`
 * definition.
 *
 * Scope: positional argument count. Does NOT validate types, named
 * args, or Thymeleaf expression syntax — those are still uncovered.
 * Worth pairing with a proper render-time smoke test in the future.
 */
class FragmentSignatureTest {

    private val templatesRoot: Path = Paths.get("src/main/resources/templates")
        .takeIf { Files.exists(it) }
        ?: Paths.get("web/src/main/resources/templates")

    @Test
    fun `every fragment caller passes the declared positional arg count`() {
        val definitions = collectFragmentDefinitions()
        assertTrue(definitions.isNotEmpty(), "expected to find at least one fragment definition under $templatesRoot")

        val callers = collectFragmentCallers()
        val problems = mutableListOf<String>()

        for (caller in callers) {
            val definition = definitions[caller.fragmentName]
            if (definition == null) {
                // Fragment names live in their own file, so a missing-name match
                // can mean either a typo OR a fragment defined in a file we
                // didn't scan. We only flag when no file in the project defines
                // the name — definitions[name] absent means truly unknown.
                problems += "${caller.location}: calls fragment `${caller.fragmentName}` but no file " +
                    "under $templatesRoot defines `th:fragment=\"${caller.fragmentName}(...)\"`"
                continue
            }
            if (caller.argCount != definition.paramCount) {
                problems += "${caller.location}: calls `${caller.fragmentName}(...)` with " +
                    "${caller.argCount} arg(s) but ${definition.location} declares " +
                    "${definition.paramCount} param(s) (`${definition.signature}`)"
            }
        }

        if (problems.isNotEmpty()) {
            fail<Unit>(
                "Thymeleaf fragment arg-count mismatch (would 500 at render time):\n" +
                    problems.joinToString("\n  - ", prefix = "  - ")
            )
        }
    }

    // ------------------------------------------------------------------------

    /** Definitions keyed by fragment name (e.g. "head" → 2 params at head.html:3). */
    private fun collectFragmentDefinitions(): Map<String, FragmentDefinition> {
        // Matches `th:fragment="name(p1, p2)"` or `th:fragment="name"`. The arg
        // list is captured as a single string and counted via `splitTopLevel`.
        val regex = Regex("""th:fragment="(\w+)(?:\(([^"]*)\))?"""")
        val out = HashMap<String, FragmentDefinition>()
        eachTemplate { path ->
            val text = Files.readString(path)
            for (match in regex.findAll(text)) {
                val name = match.groupValues[1]
                val params = match.groupValues[2]
                val count = if (params.isBlank()) 0 else splitTopLevel(params).size
                out[name] = FragmentDefinition(
                    name = name,
                    paramCount = count,
                    signature = match.value,
                    location = "${templatesRoot.relativize(path)}:" + lineOf(text, match.range.first),
                )
            }
        }
        return out
    }

    private fun collectFragmentCallers(): List<FragmentCaller> {
        // Matches `~{... :: fragmentName(args)}`. We capture everything between
        // the matching parens via a manual scan because args can themselves
        // contain commas inside strings or function calls.
        val out = mutableListOf<FragmentCaller>()
        // Quick prefilter then a targeted scan — keeps the regex simple.
        val callPrefix = Regex("""~\{[^}]*?::\s*(\w+)\(""")
        eachTemplate { path ->
            val text = Files.readString(path)
            for (match in callPrefix.findAll(text)) {
                val name = match.groupValues[1]
                val openParen = match.range.last // position of the `(`
                val argsRaw = scanBalancedParens(text, openParen) ?: continue
                val args = splitTopLevel(argsRaw)
                // Empty-paren `name()` should count as 0 args, not 1.
                val count = if (argsRaw.isBlank()) 0 else args.size
                out += FragmentCaller(
                    fragmentName = name,
                    argCount = count,
                    location = "${templatesRoot.relativize(path)}:${lineOf(text, match.range.first)}",
                )
            }
        }
        return out
    }

    private fun eachTemplate(block: (Path) -> Unit) {
        Files.walk(templatesRoot).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".html") }
                .forEach(block)
        }
    }

    /**
     * Given the index of an opening paren, return the substring up to the
     * matching close paren (excluding both). Handles nested parens, single
     * and double quotes, and Thymeleaf's `${...}` / `@{...}` / `~{...}`
     * brace-balanced expressions.
     */
    private fun scanBalancedParens(text: String, openParenIndex: Int): String? {
        var depthParen = 1
        var depthBrace = 0
        var i = openParenIndex + 1
        var inSingle = false
        var inDouble = false
        val start = i
        while (i < text.length) {
            val c = text[i]
            when {
                inSingle -> if (c == '\'' && text[i - 1] != '\\') inSingle = false
                inDouble -> if (c == '"' && text[i - 1] != '\\') inDouble = false
                c == '\'' -> inSingle = true
                c == '"' -> inDouble = true
                c == '{' -> depthBrace++
                c == '}' -> depthBrace--
                c == '(' && depthBrace == 0 -> depthParen++
                c == ')' && depthBrace == 0 -> {
                    depthParen--
                    if (depthParen == 0) return text.substring(start, i)
                }
            }
            i++
        }
        return null
    }

    /** Split on top-level commas, respecting parens / braces / quotes. */
    private fun splitTopLevel(args: String): List<String> {
        val out = mutableListOf<String>()
        var depthParen = 0
        var depthBrace = 0
        var inSingle = false
        var inDouble = false
        var lastSplit = 0
        for (i in args.indices) {
            val c = args[i]
            when {
                inSingle -> if (c == '\'' && args[i - 1] != '\\') inSingle = false
                inDouble -> if (c == '"' && args[i - 1] != '\\') inDouble = false
                c == '\'' -> inSingle = true
                c == '"' -> inDouble = true
                c == '{' -> depthBrace++
                c == '}' -> depthBrace--
                c == '(' -> depthParen++
                c == ')' -> depthParen--
                c == ',' && depthParen == 0 && depthBrace == 0 -> {
                    out += args.substring(lastSplit, i).trim()
                    lastSplit = i + 1
                }
            }
        }
        out += args.substring(lastSplit).trim()
        return out
    }

    private fun lineOf(text: String, charIndex: Int): Int {
        var line = 1
        for (i in 0 until charIndex.coerceAtMost(text.length)) {
            if (text[i] == '\n') line++
        }
        return line
    }

    private data class FragmentDefinition(
        val name: String,
        val paramCount: Int,
        val signature: String,
        val location: String,
    )

    private data class FragmentCaller(
        val fragmentName: String,
        val argCount: Int,
        val location: String,
    )
}
