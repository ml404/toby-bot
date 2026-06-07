package common.mtg.scryfall

/**
 * A tiny read-only view over a parsed JSON object — just enough of it for
 * [ScryfallCardMapper] to read a Scryfall card without caring which JSON
 * library produced the tree.
 *
 * The bot parses Scryfall responses with Gson and the web with Jackson; each
 * supplies a thin adapter implementing this interface so the card-mapping
 * rules (which fields, how double-faced cards fall back to their front face)
 * live in `common` once, instead of being re-spelled per surface where they
 * could drift.
 *
 * All accessors are forgiving by design — a missing, null, or wrong-typed
 * value reads as absent (null / empty / the supplied default) rather than
 * throwing, so a stray value in an API payload can't break card parsing.
 */
interface JsonAccessor {

    /** The string at [key], or null when absent, JSON null, or blank. */
    fun string(key: String): String?

    /** The number at [key] as a Double, or [default] when absent or non-numeric. */
    fun double(key: String, default: Double = 0.0): Double

    /** The non-blank string elements of the array at [key], or empty when absent/not an array. */
    fun stringList(key: String): List<String>

    /** The object at [key] as a nested accessor, or null when absent/not an object. */
    fun child(key: String): JsonAccessor?

    /** The object elements of the array at [key] as accessors, or empty when absent/not an array. */
    fun children(key: String): List<JsonAccessor>
}
