package bot.toby.economy

import database.dto.economy.TobyCoinPricePointDto
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Renders a handful of price points to PNG bytes and asserts the output is
 * a well-formed image. JFreeChart writes through AWT, which runs headless
 * on the CI runner, so no display is required.
 */
class TobyCoinChartRendererTest {

    private val renderer = TobyCoinChartRenderer()

    private fun point(secondsAgo: Long, price: Double) = TobyCoinPricePointDto(
        guildId = 1L,
        sampledAt = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(secondsAgo),
        price = price,
    )

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size > 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() &&
            bytes[3] == 'G'.code.toByte()

    @Test
    fun `renderPng returns a non-empty PNG for a price series`() {
        val points = listOf(
            point(0, 100.0),
            point(60, 102.5),
            point(120, 98.25),
            point(180, 110.0),
        )

        val bytes = renderer.renderPng("Test Guild", points)

        assertTrue(bytes.isNotEmpty(), "expected non-empty render")
        assertTrue(isPng(bytes), "expected a PNG magic header")
    }

    @Test
    fun `renderPng handles an empty series`() {
        val bytes = renderer.renderPng("Empty Guild", emptyList())

        assertTrue(bytes.isNotEmpty(), "expected non-empty render for empty series")
        assertTrue(isPng(bytes), "expected a PNG magic header")
    }

    @Test
    fun `renderPng honours custom dimensions`() {
        val small = renderer.renderPng("Guild", listOf(point(0, 50.0), point(60, 75.0)), width = 200, height = 120)
        val large = renderer.renderPng("Guild", listOf(point(0, 50.0), point(60, 75.0)), width = 1200, height = 600)

        assertTrue(isPng(small))
        assertTrue(isPng(large))
        // A larger canvas should not produce fewer bytes than a tiny one.
        assertTrue(large.size >= small.size, "expected larger canvas to render at least as many bytes")
    }
}
