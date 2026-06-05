package web.controller

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.ui.Model
import web.service.CardView
import web.service.CategoryAsFan
import web.service.CategoryGroup
import web.service.CubeResult
import web.service.CubeWebService
import web.service.GenerateData
import web.service.PreviewData

class CubeControllerTest {

    private lateinit var service: CubeWebService
    private lateinit var controller: CubeController

    @BeforeEach
    fun setup() {
        service = mockk()
        controller = CubeController(service)
    }

    @Test
    fun `page returns the cube template and passes displayName to the model`() {
        val model = mockk<Model>(relaxed = true)
        val user = mockk<OAuth2User> { every { getAttribute<String>("username") } returns "matt" }

        assertEquals("cube", controller.page(user, model))

        verify(exactly = 1) { model.addAttribute("username", "matt") }
    }

    @Test
    fun `page anon user falls back to literal User`() {
        val model = mockk<Model>(relaxed = true)
        controller.page(null, model)
        verify(exactly = 1) { model.addAttribute("username", "User") }
    }

    @Test
    fun `asfan returns 200 with the value on success`() {
        every { service.asFan(60, 540, 15) } returns CubeResult.ok(1.6667)
        val response = controller.asFan(60, 540, 15)
        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(response.body!!.ok)
        assertEquals(1.6667, response.body!!.value)
    }

    @Test
    fun `asfan returns 400 with the error on failure`() {
        every { service.asFan(1, 0, 15) } returns CubeResult.error("Cube size must be positive (was 0)")
        val response = controller.asFan(1, 0, 15)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertFalse(response.body!!.ok)
        assertEquals("Cube size must be positive (was 0)", response.body!!.error)
    }

    @Test
    fun `preview returns 200 with the card groups on success`() {
        val data = PreviewData(
            query = "set:vow", poolSize = 277, packSize = 15,
            groups = listOf(
                CategoryGroup(
                    "White", 50, 2.7,
                    listOf(
                        CardView("Sigarda's Splendor", "https://img/sigarda.jpg"),
                        CardView("Sungold Sentinel", null),
                    ),
                )
            ),
        )
        every { service.preview("set:vow", 15) } returns CubeResult.ok(data)

        val response = controller.preview("set:vow", 15)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(response.body!!.ok)
        assertEquals(277, response.body!!.poolSize)
        assertEquals(1, response.body!!.groups.size)
        val cards = response.body!!.groups.first().cards
        assertEquals(listOf("Sigarda's Splendor", "Sungold Sentinel"), cards.map { it.name })
        assertEquals("https://img/sigarda.jpg", cards.first().imageUrl)
    }

    @Test
    fun `preview returns 400 on failure`() {
        every { service.preview("set:zzz", 15) } returns CubeResult.error("No cards matched that query.")
        val response = controller.preview("set:zzz", 15)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertFalse(response.body!!.ok)
        assertEquals("No cards matched that query.", response.body!!.error)
    }

    @Test
    fun `generate returns 200 with packs on success`() {
        val data = GenerateData(
            query = "cube:vintage", poolSize = 540, packCount = 24, packSize = 15, balanced = true,
            packs = listOf(
                listOf(CardView("Bolt", "https://img/bolt.jpg"), CardView("Forest", null)),
                listOf(CardView("Sol Ring", "https://img/solring.jpg")),
            ),
            distribution = listOf(CategoryAsFan("Red", 100, 2.7)),
        )
        every { service.generate("cube:vintage", 24, 15, true) } returns CubeResult.ok(data)

        val response = controller.generate("cube:vintage", 24, 15, true)

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body!!
        assertTrue(body.ok)
        assertEquals(24, body.packCount)
        assertEquals(2, body.packs.size)
        assertEquals(listOf("Bolt", "Forest"), body.packs.first().map { it.name })
        assertEquals("https://img/bolt.jpg", body.packs.first().first().imageUrl)
    }

    @Test
    fun `generate returns 400 when the pool is too small`() {
        every { service.generate("t:angel", 24, 15, true) } returns
            CubeResult.error("Not enough cards: need 360 but the pool only has 30.")
        val response = controller.generate("t:angel", 24, 15, true)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertFalse(response.body!!.ok)
        assertTrue(response.body!!.packs.isEmpty())
    }

    @Test
    fun `generate forwards the raw params to the service`() {
        every { service.generate("set:vow", 8, 15, false) } returns
            CubeResult.error("whatever")
        controller.generate("set:vow", 8, 15, false)
        verify(exactly = 1) { service.generate("set:vow", 8, 15, false) }
    }
}
