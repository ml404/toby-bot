package web.profile

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.stereotype.Component
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Renders a [ProfileCardData] snapshot as a 900×400 PNG.
 *
 * Drawn directly with [Graphics2D] (not JFreeChart — the chart library
 * is the wrong abstraction for a free-form layout). Mirrors the shape
 * of [bot.toby.economy.TobyCoinChartRenderer]: a small `@Component`
 * with `fun renderPng(...): ByteArray` returning the bytes that
 * `FileUpload.fromData(...)` consumes JDA-side and that the web PNG
 * endpoint streams to the browser.
 *
 * Fonts are loaded once at construction from classpath resources under
 * `/fonts/` (`web/src/main/resources/fonts/`). **Init throws** when
 * either Inter font file is missing — AWT silently falls back to
 * SansSerif otherwise, which would ship the wrong typography to prod
 * without anyone noticing locally.
 */
@Component
class ProfileCardRenderer {

    private val regularFont: Font = loadFont("/fonts/Inter-Regular.ttf")
    private val boldFont: Font = loadFont("/fonts/Inter-Bold.ttf")

    // Discord OG-preview crawlers can re-hit /card.png repeatedly within seconds,
    // and a busy guild's leaderboard page can render dozens of avatars from a
    // small pool of recurring URLs. Caching the decoded BufferedImage avoids
    // both the HTTP fetch and the transient avatar buffer per render. Cap
    // matches expected unique-author working sets on a small instance; TTL is
    // long enough to absorb embed-preview bursts, short enough that an updated
    // Discord avatar refreshes within minutes.
    private val avatarCache: Cache<String, BufferedImage> = Caffeine.newBuilder()
        .maximumSize(AVATAR_CACHE_MAX)
        .expireAfterWrite(AVATAR_CACHE_TTL_MIN, TimeUnit.MINUTES)
        .build()

    fun renderPng(data: ProfileCardData): ByteArray {
        // TYPE_INT_RGB drops the alpha channel — saves 25% per-render heap vs
        // TYPE_INT_ARGB on a 224 MB cap. drawBackground compensates by filling
        // the canvas with BG_BOTTOM before the rounded gradient so the four
        // corners outside the round-rect blend with Discord's dark theme
        // instead of defaulting to opaque black.
        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            enableQualityHints(g)
            drawBackground(g)
            val tier = tierFor(data.level)
            drawAvatar(g, data.avatarUrl)
            drawHeader(g, data, tier)
            drawProgressBar(g, data, tier)
            drawBalanceAndTitle(g, data)
            drawAchievements(g, data.recentAchievements)
        } finally {
            g.dispose()
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    // ---- layout helpers ----

    private fun drawBackground(g: Graphics2D) {
        // Backdrop fill — pairs with TYPE_INT_RGB so the four corners outside
        // the rounded rect aren't harsh black.
        g.color = BG_BOTTOM
        g.fillRect(0, 0, WIDTH, HEIGHT)
        g.paint = GradientPaint(0f, 0f, BG_TOP, 0f, HEIGHT.toFloat(), BG_BOTTOM)
        g.fill(RoundRectangle2D.Float(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), 32f, 32f))
        // Subtle inner accent at the top edge for some depth.
        g.color = Color(255, 255, 255, 14)
        g.fillRect(0, 0, WIDTH, 80)
    }

    private fun drawAvatar(g: Graphics2D, avatarUrl: String) {
        val avatar = loadAvatar(avatarUrl)
        val cx = AVATAR_X + AVATAR_SIZE / 2
        val cy = AVATAR_Y + AVATAR_SIZE / 2
        // Glow ring around the avatar.
        g.color = Color(255, 255, 255, 28)
        g.fill(Ellipse2D.Float(
            (AVATAR_X - 6).toFloat(), (AVATAR_Y - 6).toFloat(),
            (AVATAR_SIZE + 12).toFloat(), (AVATAR_SIZE + 12).toFloat()
        ))
        // Clip to a circle and draw the avatar.
        val clip = g.clip
        g.clip(Ellipse2D.Float(AVATAR_X.toFloat(), AVATAR_Y.toFloat(), AVATAR_SIZE.toFloat(), AVATAR_SIZE.toFloat()))
        if (avatar != null) {
            g.drawImage(avatar, AVATAR_X, AVATAR_Y, AVATAR_SIZE, AVATAR_SIZE, null)
        } else {
            // Fallback grey disc when the avatar URL didn't load.
            g.color = Color(0x40, 0x44, 0x4B)
            g.fillOval(AVATAR_X, AVATAR_Y, AVATAR_SIZE, AVATAR_SIZE)
        }
        g.clip = clip
        // Border ring.
        g.color = Color(255, 255, 255, 60)
        g.drawOval(AVATAR_X, AVATAR_Y, AVATAR_SIZE, AVATAR_SIZE)
        // Mark unused to keep the linter quiet without changing semantics.
        @Suppress("UNUSED_VARIABLE") val unused = cx + cy
    }

    private fun drawHeader(g: Graphics2D, data: ProfileCardData, tier: Tier) {
        g.color = Color.WHITE
        g.font = boldFont.deriveFont(34f)
        val maxNameWidth = WIDTH - HEADER_X - 250 // leave room for the tier badge
        val name = truncateToWidth(g, data.displayName, maxNameWidth)
        g.drawString(name, HEADER_X, HEADER_Y)

        g.font = regularFont.deriveFont(16f)
        g.color = Color(0xB9, 0xBB, 0xBE)
        g.drawString(data.guildName, HEADER_X, HEADER_Y + 26)

        // Tier badge — pill on the right of the header row.
        val badgeText = "Lv ${data.level} · ${tier.label}"
        g.font = boldFont.deriveFont(15f)
        val padX = 14
        val padY = 6
        val badgeWidth = g.fontMetrics.stringWidth(badgeText) + padX * 2
        val badgeHeight = g.fontMetrics.height + padY
        val badgeX = WIDTH - badgeWidth - 40
        val badgeY = HEADER_Y - 28
        g.paint = GradientPaint(
            badgeX.toFloat(), badgeY.toFloat(), tier.gradientFrom,
            (badgeX + badgeWidth).toFloat(), (badgeY + badgeHeight).toFloat(), tier.gradientTo
        )
        g.fill(RoundRectangle2D.Float(
            badgeX.toFloat(), badgeY.toFloat(),
            badgeWidth.toFloat(), badgeHeight.toFloat(), 18f, 18f
        ))
        g.color = Color(0, 0, 0, 160)
        g.drawString(badgeText, badgeX + padX, badgeY + badgeHeight - padY)
    }

    private fun drawProgressBar(g: Graphics2D, data: ProfileCardData, tier: Tier) {
        val y = PROGRESS_Y
        val x = HEADER_X
        val w = WIDTH - x - 40
        val h = 22
        // Track.
        g.color = Color(0xFF, 0xFF, 0xFF, 36)
        g.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), h.toFloat(), h.toFloat()))
        // Fill.
        val frac = if (data.xpForNextLevel > 0)
            (data.xpIntoLevel.toDouble() / data.xpForNextLevel.toDouble()).coerceIn(0.0, 1.0)
        else 0.0
        val fillW = (w * frac).toInt().coerceAtLeast(if (data.xpIntoLevel > 0) 6 else 0)
        if (fillW > 0) {
            g.paint = GradientPaint(
                x.toFloat(), y.toFloat(), tier.gradientFrom,
                (x + w).toFloat(), y.toFloat(), tier.gradientTo
            )
            g.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), fillW.toFloat(), h.toFloat(), h.toFloat(), h.toFloat()))
        }
        // Caption above the bar.
        g.color = Color(0xB9, 0xBB, 0xBE)
        g.font = regularFont.deriveFont(14f)
        val pct = (frac * 100).toInt()
        g.drawString("${data.xpIntoLevel} / ${data.xpForNextLevel} XP ($pct%)", x, y - 8)
        // Lifetime XP on the right.
        val total = "${data.totalXp} lifetime XP"
        val totalW = g.fontMetrics.stringWidth(total)
        g.drawString(total, x + w - totalW, y - 8)
    }

    private fun drawBalanceAndTitle(g: Graphics2D, data: ProfileCardData) {
        val y = BALANCE_Y
        val x = HEADER_X
        drawPill(g, x, y, "💰 ${data.socialCredit} credits", Color(0x57, 0xF2, 0x87, 200))
        val title = data.equippedTitle
        if (title != null) {
            val titleColor = parseHexColor(title.colorHex) ?: Color(0x5B, 0x8D, 0xEF, 220)
            drawPill(g, x + 220, y, "⭐ ${title.label}", titleColor)
        }
    }

    private fun drawAchievements(g: Graphics2D, achievements: List<ProfileCardData.AchievementSnapshot>) {
        val sectionY = ACHIEVEMENTS_Y
        g.color = Color(0xB9, 0xBB, 0xBE)
        g.font = boldFont.deriveFont(14f)
        g.drawString("RECENT ACHIEVEMENTS", HEADER_X, sectionY)

        if (achievements.isEmpty()) {
            g.color = Color(0x88, 0x8A, 0x8E)
            g.font = regularFont.deriveFont(16f)
            g.drawString("No achievements unlocked yet — keep playing!", HEADER_X, sectionY + 28)
            return
        }
        g.font = regularFont.deriveFont(17f)
        achievements.take(3).forEachIndexed { i, a ->
            val rowY = sectionY + 26 + i * 26
            val icon = a.icon ?: "🏆"
            g.color = Color.WHITE
            g.drawString("$icon  ${truncateToWidth(g, a.name, WIDTH - HEADER_X - 40)}", HEADER_X, rowY)
        }
    }

    private fun drawPill(g: Graphics2D, x: Int, y: Int, text: String, accent: Color) {
        g.font = boldFont.deriveFont(15f)
        val fm = g.fontMetrics
        val padX = 16
        val padY = 8
        val w = fm.stringWidth(text) + padX * 2
        val h = fm.height + padY
        g.color = Color(accent.red, accent.green, accent.blue, 60)
        g.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), 14f, 14f))
        g.color = accent
        g.drawString(text, x + padX, y + h - padY + 1)
    }

    private fun enableQualityHints(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.composite = AlphaComposite.SrcOver
    }

    private fun loadAvatar(url: String): BufferedImage? {
        avatarCache.getIfPresent(url)?.let { return it }
        val fetched = runCatching {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = AVATAR_TIMEOUT_MS
            conn.readTimeout = AVATAR_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "TobyBot-ProfileCard")
            conn.inputStream.use { ImageIO.read(it) }
        }.getOrNull() ?: return null
        avatarCache.put(url, fetched)
        return fetched
    }

    private fun loadFont(resource: String): Font {
        val stream = javaClass.getResourceAsStream(resource)
            ?: error("Missing classpath font resource: $resource — drop the Inter TTF into discord-bot/src/main/resources$resource. AWT would silently fall back to SansSerif.")
        return stream.use { Font.createFont(Font.TRUETYPE_FONT, it) }
    }

    private fun truncateToWidth(g: Graphics2D, text: String, maxWidth: Int): String {
        val fm = g.fontMetrics
        if (fm.stringWidth(text) <= maxWidth) return text
        val ellipsis = "…"
        val budget = maxWidth - fm.stringWidth(ellipsis)
        if (budget <= 0) return ellipsis
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (fm.stringWidth(text.substring(0, mid)) <= budget) lo = mid else hi = mid - 1
        }
        return text.substring(0, lo) + ellipsis
    }

    private fun parseHexColor(hex: String?): Color? {
        if (hex.isNullOrBlank()) return null
        val stripped = hex.removePrefix("#")
        if (stripped.length != 6) return null
        return runCatching { Color(stripped.toInt(16)) }.getOrNull()
    }

    private fun tierFor(level: Int): Tier = when {
        level >= 50 -> Tier.DIAMOND
        level >= 25 -> Tier.GOLD
        level >= 10 -> Tier.SILVER
        else -> Tier.BRONZE
    }

    /**
     * Visual tiers mirror the four CSS classes in `profile.css`
     * (`level-tier-bronze / silver / gold / diamond`) so the Discord PNG
     * and the HTML profile page agree on which level wears which colour.
     */
    private enum class Tier(
        val label: String,
        val gradientFrom: Color,
        val gradientTo: Color,
    ) {
        BRONZE("Bronze", Color(0xC9, 0x80, 0x3A), Color(0xE6, 0xA8, 0x6B)),
        SILVER("Silver", Color(0x9A, 0xA3, 0xB2), Color(0xD3, 0xDA, 0xE6)),
        GOLD("Gold", Color(0xD4, 0xA0, 0x17), Color(0xFF, 0xD9, 0x66)),
        DIAMOND("Diamond", Color(0x58, 0x65, 0xF2), Color(0xA6, 0xB1, 0xFF)),
    }

    companion object {
        const val WIDTH = 900
        const val HEIGHT = 400

        private const val AVATAR_X = 40
        private const val AVATAR_Y = 40
        private const val AVATAR_SIZE = 160

        private const val HEADER_X = 230
        private const val HEADER_Y = 90

        private const val PROGRESS_Y = 165
        private const val BALANCE_Y = 200
        private const val ACHIEVEMENTS_Y = 260

        private const val AVATAR_TIMEOUT_MS = 2_000

        // Avatar BufferedImage cache. 256 distinct URLs at typical 128×128 ARGB
        // (~64 KB each) gives a ~16 MB worst-case ceiling, but realistic working
        // sets are dozens of recurring URLs (well under 2 MB resident).
        private const val AVATAR_CACHE_MAX = 256L
        private const val AVATAR_CACHE_TTL_MIN = 5L

        private val BG_TOP = Color(0x2B, 0x2D, 0x31)
        private val BG_BOTTOM = Color(0x1E, 0x1F, 0x22)
    }
}
