package web.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DuelRedirectControllerTest {

    private val controller = DuelRedirectController()

    @Test
    fun `guildList without pick redirects to pvp guilds`() {
        assertEquals("redirect:/pvp/guilds", controller.guildList(pick = false))
    }

    @Test
    fun `guildList with pick preserves the pick query`() {
        assertEquals("redirect:/pvp/guilds?pick=true", controller.guildList(pick = true))
    }

    @Test
    fun `per-guild page redirects to pvp guildId`() {
        assertEquals("redirect:/pvp/42", controller.page(42L))
    }

    @Test
    fun `pending feed redirects under the duel game segment`() {
        assertEquals("redirect:/pvp/42/duel/pending", controller.pending(42L))
    }

    @Test
    fun `outgoing feed redirects under the duel game segment`() {
        assertEquals("redirect:/pvp/42/duel/outgoing", controller.outgoing(42L))
    }

    @Test
    fun `challenge POST redirects under the duel game segment`() {
        assertEquals("redirect:/pvp/42/duel/challenge", controller.challenge(42L))
    }

    @Test
    fun `accept POST redirects under the duel game segment`() {
        assertEquals("redirect:/pvp/42/duel/99/accept", controller.accept(42L, 99L))
    }

    @Test
    fun `decline POST redirects under the duel game segment`() {
        assertEquals("redirect:/pvp/42/duel/99/decline", controller.decline(42L, 99L))
    }

    @Test
    fun `cancel POST redirects under the duel game segment`() {
        assertEquals("redirect:/pvp/42/duel/99/cancel", controller.cancel(42L, 99L))
    }
}
