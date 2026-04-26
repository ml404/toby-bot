package bot.toby.managers

import bot.toby.helpers.UserDtoHelper
import core.button.Button
import core.button.ButtonContext
import database.dto.UserDto
import database.service.ConfigService
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Confirms the colon-prefix routing required by stateful component IDs
 * such as `highlow:HIGHER:9:50:6` and `duel:accept:1:42`. With exact
 * equals, every existing button using a colon-suffixed component id
 * would silently fail to dispatch.
 */
class DefaultButtonManagerTest {

    private class FakeButton(override val name: String) : Button {
        override val description: String = "test"
        override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) = Unit
    }

    private fun manager(vararg buttons: Button): DefaultButtonManager {
        val configService = mockk<ConfigService>(relaxed = true)
        val userDtoHelper = mockk<UserDtoHelper>(relaxed = true)
        return DefaultButtonManager(configService, userDtoHelper, buttons.toList())
    }

    @Test
    fun `getButton resolves stateful highlow componentId via colon-prefix`() {
        val highlow = FakeButton("highlow")
        val mgr = manager(highlow)

        assertEquals(highlow, mgr.getButton("highlow:HIGHER:9:50:6"))
    }

    @Test
    fun `getButton resolves stateful duel componentId via colon-prefix`() {
        val duel = FakeButton("duel")
        val mgr = manager(duel)

        assertEquals(duel, mgr.getButton("duel:accept:1:42"))
        assertEquals(duel, mgr.getButton("duel:decline:1:42"))
    }

    @Test
    fun `getButton matches exact name when no colon present`() {
        val plain = FakeButton("plain")
        val mgr = manager(plain)

        assertEquals(plain, mgr.getButton("plain"))
    }

    @Test
    fun `getButton returns null when no button matches`() {
        val mgr = manager(FakeButton("highlow"), FakeButton("duel"))

        assertNull(mgr.getButton("nonexistent:whatever"))
        assertNull(mgr.getButton("nonexistent"))
    }

    @Test
    fun `getButton matching is case-insensitive on the prefix`() {
        val duel = FakeButton("duel")
        val mgr = manager(duel)

        assertEquals(duel, mgr.getButton("DUEL:ACCEPT:1:42"))
        assertEquals(duel, mgr.getButton("Duel:Accept:1:42"))
    }
}
