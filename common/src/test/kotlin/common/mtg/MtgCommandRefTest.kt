package common.mtg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Locks the single source of truth for Magic command names: the pre-formatted
 * "/command subcommand" copy strings must compose from the bare tokens, so the
 * Discord registration and the user-facing copy stay in lockstep.
 */
class MtgCommandRefTest {

    @Test
    fun `formatted strings compose from the command and subcommand tokens`() {
        assertEquals("/${MtgCommandRef.CARD} ${MtgCommandRef.Card.LOOKUP}", MtgCommandRef.CARD_LOOKUP)
        assertEquals("/${MtgCommandRef.CARD} ${MtgCommandRef.Card.RULINGS}", MtgCommandRef.CARD_RULINGS)
        assertEquals("/${MtgCommandRef.CARD} ${MtgCommandRef.Card.COMBOS}", MtgCommandRef.CARD_COMBOS)
        assertEquals("/${MtgCommandRef.DECK} ${MtgCommandRef.Deck.LEGALITY}", MtgCommandRef.DECK_LEGALITY)
        assertEquals("/${MtgCommandRef.MTG} ${MtgCommandRef.Reference.SET}", MtgCommandRef.MTG_SET)
        assertEquals("/${MtgCommandRef.MTG} ${MtgCommandRef.Reference.RULE}", MtgCommandRef.MTG_RULE)
        assertEquals("/${MtgCommandRef.PRICEWATCH} ${MtgCommandRef.PriceWatch.ADD}", MtgCommandRef.PRICEWATCH_ADD)
        assertEquals("/${MtgCommandRef.PRICEWATCH} ${MtgCommandRef.PriceWatch.LIST}", MtgCommandRef.PRICEWATCH_LIST)
        assertEquals("/${MtgCommandRef.PRICEWATCH} ${MtgCommandRef.PriceWatch.REMOVE}", MtgCommandRef.PRICEWATCH_REMOVE)
    }

    @Test
    fun `the expected current command and subcommand names`() {
        assertEquals("/card lookup", MtgCommandRef.CARD_LOOKUP)
        assertEquals("/deck legality", MtgCommandRef.DECK_LEGALITY)
        assertEquals("/mtg set", MtgCommandRef.MTG_SET)
        assertEquals("/pricewatch add", MtgCommandRef.PRICEWATCH_ADD)
        assertEquals("pricewatch", MtgCommandRef.PRICEWATCH)
    }
}
