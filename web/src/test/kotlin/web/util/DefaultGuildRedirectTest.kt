package web.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Pure-function decision table for the auto-redirect rule. Pinning each
 * row here means every picker controller's redirect logic shrinks to
 * "call DefaultGuildRedirect.pick and let it answer", and a regression
 * in this rule trips one test instead of fourteen controller tests.
 */
internal class DefaultGuildRedirectTest {

    @Test
    fun `pick=true always returns null (force the picker)`() {
        assertNull(DefaultGuildRedirect.pick(listOf(1L), cookieGuildId = 1L, pick = true))
        assertNull(DefaultGuildRedirect.pick(listOf(1L, 2L), cookieGuildId = 1L, pick = true))
        assertNull(DefaultGuildRedirect.pick(emptyList(), cookieGuildId = null, pick = true))
    }

    @Test
    fun `empty guild list returns null (no mutual guilds — empty hero will render)`() {
        assertNull(DefaultGuildRedirect.pick(emptyList(), cookieGuildId = 1L, pick = false))
        assertNull(DefaultGuildRedirect.pick(emptyList(), cookieGuildId = null, pick = false))
    }

    @Test
    fun `valid cookie wins when the user still shares that guild`() {
        assertEquals(222L, DefaultGuildRedirect.pick(listOf(111L, 222L, 333L), cookieGuildId = 222L, pick = false))
    }

    @Test
    fun `stale cookie falls through to the single-guild rule`() {
        // Cookie points to a guild the user no longer shares.
        // With only one mutual guild left, we still auto-redirect to it.
        assertEquals(111L, DefaultGuildRedirect.pick(listOf(111L), cookieGuildId = 999L, pick = false))
    }

    @Test
    fun `stale cookie with multi-guild returns null (picker required)`() {
        assertNull(DefaultGuildRedirect.pick(listOf(111L, 222L), cookieGuildId = 999L, pick = false))
    }

    @Test
    fun `single mutual guild auto-redirects even without a cookie`() {
        assertEquals(42L, DefaultGuildRedirect.pick(listOf(42L), cookieGuildId = null, pick = false))
    }

    @Test
    fun `multiple guilds without a cookie returns null (picker)`() {
        assertNull(DefaultGuildRedirect.pick(listOf(1L, 2L), cookieGuildId = null, pick = false))
        assertNull(DefaultGuildRedirect.pick(listOf(1L, 2L, 3L, 4L), cookieGuildId = null, pick = false))
    }
}
