package web.controller

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.ui.Model
import web.service.MemeResult
import web.service.UtilsResult
import web.service.UtilsWebService

class UtilsControllerTest {

    private lateinit var utilsWebService: UtilsWebService
    private lateinit var controller: UtilsController

    @BeforeEach
    fun setup() {
        utilsWebService = mockk()
        controller = UtilsController(utilsWebService)
    }

    @Test
    fun `page returns the utils template and passes displayName to the model`() {
        val model = mockk<Model>(relaxed = true)
        val user = mockk<OAuth2User> { every { getAttribute<String>("username") } returns "matt" }

        assertEquals("utils", controller.page(user, model))

        verify(exactly = 1) { model.addAttribute("username", "matt") }
    }

    @Test
    fun `page anon user falls back to literal User`() {
        val model = mockk<Model>(relaxed = true)

        controller.page(null, model)

        verify(exactly = 1) { model.addAttribute("username", "User") }
    }

    @Test
    fun `meme endpoint returns 200 ok with the meme payload on service success`() {
        val meme = MemeResult(
            title = "look at this dog", author = "matt",
            imageUrl = "https://i.redd.it/x.jpg",
            permalink = "https://reddit.com/r/memes/comments/abc",
            subreddit = "memes",
        )
        every { utilsWebService.randomMeme("memes", "week", 25) } returns UtilsResult.ok(meme)

        val response = controller.meme("memes", "week", 25)

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body!!
        assertTrue(body.ok)
        assertNull(body.error)
        assertEquals(meme, body.meme)
    }

    @Test
    fun `meme endpoint returns 400 with the service error on service failure`() {
        every { utilsWebService.randomMeme("evil", "day", 10) } returns UtilsResult.error("Invalid subreddit name.")

        val response = controller.meme("evil", "day", 10)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        val body = response.body!!
        assertFalse(body.ok)
        assertEquals("Invalid subreddit name.", body.error)
        assertNull(body.meme)
    }

    @Test
    fun `meme endpoint forwards the raw query params to the service unchanged`() {
        // The service does the validation; the controller must not
        // pre-process — otherwise an empty subreddit would never reach
        // the validation branch and the error message would be wrong.
        every { utilsWebService.randomMeme("", "day", 10) } returns UtilsResult.error("Subreddit is required.")

        controller.meme("", "day", 10)

        verify(exactly = 1) { utilsWebService.randomMeme("", "day", 10) }
    }
}
