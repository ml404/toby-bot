package common.economy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class CoinTest {

    @Test
    fun `default coin is TOBY`() {
        assertEquals(Coin.TOBY, Coin.DEFAULT)
    }

    @Test
    fun `fromSymbol resolves every catalogue ticker, case-insensitively`() {
        Coin.entries.forEach { coin ->
            assertEquals(coin, Coin.fromSymbol(coin.symbol))
            assertEquals(coin, Coin.fromSymbol(coin.symbol.lowercase()))
            assertEquals(coin, Coin.fromSymbol(coin.symbol.replaceFirstChar { it.lowercase() }))
        }
    }

    @Test
    fun `fromSymbol falls back to the default for null, blank or unknown codes`() {
        assertEquals(Coin.DEFAULT, Coin.fromSymbol(null))
        assertEquals(Coin.DEFAULT, Coin.fromSymbol(""))
        assertEquals(Coin.DEFAULT, Coin.fromSymbol("   "))
        assertEquals(Coin.DEFAULT, Coin.fromSymbol("DOGE"))
        assertEquals(Coin.DEFAULT, Coin.fromSymbol("toby-coin"))
    }

    @Test
    fun `symbol mirrors the enum name and tickers are unique`() {
        Coin.entries.forEach { assertEquals(it.name, it.symbol) }
        val symbols = Coin.entries.map { it.symbol }
        assertEquals(symbols.size, symbols.toSet().size, "tickers must be unique")
    }

    @Test
    fun `every coin carries sane, positive parameters and copy`() {
        Coin.entries.forEach { coin ->
            assertTrue(coin.initialPrice > 0.0, "${coin.symbol} initialPrice")
            assertTrue(coin.volatility > 0.0, "${coin.symbol} volatility")
            assertTrue(coin.tradeImpact > 0.0, "${coin.symbol} tradeImpact")
            assertTrue(coin.displayName.isNotBlank(), "${coin.symbol} displayName")
            assertTrue(coin.riskLabel.isNotBlank(), "${coin.symbol} riskLabel")
            assertTrue(coin.blurb.isNotBlank(), "${coin.symbol} blurb")
        }
    }

    @Test
    fun `the roster forms a strict risk spectrum on volatility`() {
        assertTrue(Coin.TOBL.volatility < Coin.TOBY.volatility, "TOBL calmer than TOBY")
        assertTrue(Coin.TOBY.volatility < Coin.RUFF.volatility, "TOBY calmer than RUFF")
        assertTrue(Coin.RUFF.volatility < Coin.MOON.volatility, "RUFF calmer than MOON")
    }

    @Test
    fun `trade impact rises across the same risk spectrum`() {
        assertTrue(Coin.TOBL.tradeImpact < Coin.TOBY.tradeImpact)
        assertTrue(Coin.TOBY.tradeImpact < Coin.RUFF.tradeImpact)
        assertTrue(Coin.RUFF.tradeImpact < Coin.MOON.tradeImpact)
    }

    @Test
    fun `TOBY keeps the legacy single-coin parameters`() {
        // Regression: the rest of the bot still settles in TOBY, so its
        // numbers must match the original constants exactly.
        assertEquals(TobyCoinEngine.VOLATILITY, Coin.TOBY.volatility, 1e-12)
        assertEquals(TobyCoinEngine.TRADE_IMPACT, Coin.TOBY.tradeImpact, 1e-12)
        assertEquals(TobyCoinEngine.INITIAL_PRICE, Coin.TOBY.initialPrice, 1e-12)
    }

    @Test
    fun `risk labels are distinct per coin`() {
        val labels = Coin.entries.map { it.riskLabel }
        assertEquals(labels.size, labels.toSet().size)
        assertNotEquals(Coin.TOBY.riskLabel, Coin.MOON.riskLabel)
    }
}
