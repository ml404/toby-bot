package common.mtg

/**
 * Single source of truth for the Magic command + subcommand names.
 *
 * Both the Discord command registration (`name` / `SubcommandData`) and every
 * user-facing mention of a command — embeds, the card-price-watch notification
 * preference, and the web Magic toolkit page — reference these constants, so a
 * rename happens in exactly one place and the copy can never drift out of sync
 * with the commands (the bug that motivated this: the #656 split renamed the
 * subcommands but the prose still said `/cube card`, `/cube watch-add`, …).
 *
 * The bare tokens (e.g. [Card.LOOKUP] = `"lookup"`) drive registration; the
 * pre-formatted [CARD_LOOKUP] = `"/card lookup"` strings are for copy. All are
 * `const`, so they inline and can be referenced from enum constructors and
 * other `const val`s.
 */
object MtgCommandRef {

    // Top-level command names.
    const val CUBE = "cube"
    const val CARD = "card"
    const val DECK = "deck"
    const val MTG = "mtg"
    const val PRICEWATCH = "pricewatch"

    // Subcommand tokens, grouped by their parent command.
    object Cube {
        const val ASFAN = "asfan"
        const val PREVIEW = "preview"
        const val GENERATE = "generate"
        const val SAVED = "saved"
    }

    object Card {
        const val LOOKUP = "lookup"
        const val RULINGS = "rulings"
        const val COMBOS = "combos"
    }

    object Deck {
        const val LEGALITY = "legality"
    }

    object Reference {
        const val SET = "set"
        const val RULE = "rule"
    }

    object PriceWatch {
        const val ADD = "add"
        const val LIST = "list"
        const val REMOVE = "remove"
    }

    // Pre-formatted "/command subcommand" strings for user-facing copy.
    const val CARD_LOOKUP = "/$CARD ${Card.LOOKUP}"
    const val CARD_RULINGS = "/$CARD ${Card.RULINGS}"
    const val CARD_COMBOS = "/$CARD ${Card.COMBOS}"
    const val DECK_LEGALITY = "/$DECK ${Deck.LEGALITY}"
    const val MTG_SET = "/$MTG ${Reference.SET}"
    const val MTG_RULE = "/$MTG ${Reference.RULE}"
    const val PRICEWATCH_ADD = "/$PRICEWATCH ${PriceWatch.ADD}"
    const val PRICEWATCH_LIST = "/$PRICEWATCH ${PriceWatch.LIST}"
    const val PRICEWATCH_REMOVE = "/$PRICEWATCH ${PriceWatch.REMOVE}"
}
