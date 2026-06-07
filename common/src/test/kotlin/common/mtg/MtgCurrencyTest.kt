package common.mtg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MtgCurrencyTest {

    @Test
    fun `default currency is USD`() {
        assertEquals(MtgCurrency.USD, MtgCurrency.DEFAULT)
    }

    @Test
    fun `fromCode resolves a code or display name, case-insensitively`() {
        assertEquals(MtgCurrency.USD, MtgCurrency.fromCode("usd"))
        assertEquals(MtgCurrency.EUR, MtgCurrency.fromCode("EUR"))
        assertEquals(MtgCurrency.TIX, MtgCurrency.fromCode("  Tix  "))
        // Display names resolve too, so a stored "USD" reads back.
        assertEquals(MtgCurrency.USD, MtgCurrency.fromCode("USD"))
    }

    @Test
    fun `fromCode returns null for unknown, blank or null input`() {
        assertNull(MtgCurrency.fromCode("gbp"))
        assertNull(MtgCurrency.fromCode(""))
        assertNull(MtgCurrency.fromCode(null))
    }

    @Test
    fun `symbol and suffix format each currency distinctly`() {
        assertEquals("$", MtgCurrency.USD.symbol)
        assertEquals("", MtgCurrency.USD.suffix)
        assertEquals("€", MtgCurrency.EUR.symbol)
        assertEquals(" tix", MtgCurrency.TIX.suffix)
    }

    @Test
    fun `format renders a Double to two decimals with the currency's symbol and suffix`() {
        assertEquals("$1.50", MtgCurrency.USD.format(1.5))
        assertEquals("€1.50", MtgCurrency.EUR.format(1.5))
        assertEquals("0.03 tix", MtgCurrency.TIX.format(0.03))
        // Rounds to two decimals like the surfaces expect.
        assertEquals("$1.90", MtgCurrency.USD.format(1.895))
        assertEquals("$0.00", MtgCurrency.USD.format(0.0))
    }

    @Test
    fun `wrap decorates an already-formatted price string without reformatting it`() {
        assertEquals("$1.50", MtgCurrency.USD.wrap("1.50"))
        assertEquals("€1.20", MtgCurrency.EUR.wrap("1.20"))
        assertEquals("0.03 tix", MtgCurrency.TIX.wrap("0.03"))
    }
}
