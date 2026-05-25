package web.controller

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.ui.Model

class DndLookupControllerTest {

    private val controller = DndLookupController()

    @Test
    fun `page returns the dndLookup template`() {
        val model = mockk<Model>(relaxed = true)
        val user = mockk<OAuth2User> { every { getAttribute<String>("username") } returns "matt" }

        assertEquals("dndLookup", controller.page(user, model))
    }

    @Test
    fun `page passes the user's displayName to the model`() {
        val nameSlot = slot<String>()
        val model = mockk<Model>(relaxed = true)
        every { model.addAttribute("username", capture(nameSlot)) } returns model
        val user = mockk<OAuth2User> { every { getAttribute<String>("username") } returns "matt" }

        controller.page(user, model)

        assertEquals("matt", nameSlot.captured)
    }

    @Test
    fun `page falls back to the literal "User" when the OAuth principal is null (anon access)`() {
        val nameSlot = slot<String>()
        val model = mockk<Model>(relaxed = true)
        every { model.addAttribute("username", capture(nameSlot)) } returns model

        controller.page(null, model)

        assertEquals("User", nameSlot.captured)
    }

    @Test
    fun `legacy campaign URLs redirect to the new lookup page so bookmarks do not 404`() {
        // Source comment: "Old campaign URLs redirect to the new lookup
        // page so existing bookmarks and Discord-posted links don't 404."
        // One handler serves every path in the @GetMapping list — this
        // asserts the redirect target stays as documented.
        assertEquals("redirect:/dnd", controller.campaignRedirect())
    }
}
