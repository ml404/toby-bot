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
}
