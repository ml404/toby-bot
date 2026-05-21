package web.template

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.support.GenericApplicationContext
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.web.servlet.JakartaServletWebApplication

/**
 * Pins the SSE-notification wiring in the shared `<head>` fragment.
 * Logged-out pages must not request the per-user SSE stream
 * (no auth-meta tag, no notifications-stream.js include) but must still
 * load the toast UI (per-page callsites in economy/blackjack/etc.
 * depend on `window.TobyToasts`). Logged-in pages must wire all three.
 */
class HeadFragmentNotificationsTest {

    private val servletContext = MockServletContext()
    private val webApp = JakartaServletWebApplication.buildApplication(servletContext)
    private val engine: SpringTemplateEngine = SpringTemplateEngine().apply {
        val appCtx = GenericApplicationContext().also { it.refresh() }
        val resolver = SpringResourceTemplateResolver().apply {
            setApplicationContext(appCtx)
            prefix = "classpath:/templates/"
            suffix = ".html"
            templateMode = TemplateMode.HTML
            characterEncoding = "UTF-8"
            isCacheable = false
        }
        setTemplateResolver(resolver)
    }

    private fun renderHead(username: String?): String {
        val request = MockHttpServletRequest(servletContext)
        val response = MockHttpServletResponse()
        val exchange = webApp.buildExchange(request, response)
        val ctx = WebContext(exchange).apply {
            if (username != null) setVariable("username", username)
            setVariable("pageTitle", "Test page")
            setVariable("extraCss", null)
        }
        return engine.process("fragments/head", setOf("head"), ctx)
    }

    private fun renderHeadSeo(username: String?): String {
        val request = MockHttpServletRequest(servletContext)
        val response = MockHttpServletResponse()
        val exchange = webApp.buildExchange(request, response)
        val ctx = WebContext(exchange).apply {
            if (username != null) setVariable("username", username)
            setVariable("pageTitle", "Test page")
            setVariable("pageDescription", "Test description")
            setVariable("ogImage", null)
            setVariable("extraCss", null)
        }
        return engine.process("fragments/head", setOf("headSeo"), ctx)
    }

    @Test
    fun `anonymous head fragment omits the user-authenticated meta tag`() {
        val html = renderHead(username = null)
        assertFalse(
            html.contains("name=\"user-authenticated\""),
            "Anonymous pages must not emit the user-authenticated meta — that would trick the SSE script into opening a 401-doomed stream.",
        )
    }

    @Test
    fun `anonymous head fragment omits the notifications-stream script include`() {
        val html = renderHead(username = null)
        assertFalse(
            html.contains("/js/notifications-stream.js\""),
            "Anonymous pages must skip the SSE script to avoid a wasted network hit.",
        )
    }

    @Test
    fun `anonymous head fragment still includes the toasts script for per-page callsites`() {
        val html = renderHead(username = null)
        assertTrue(
            html.contains("/js/toasts.js\""),
            "Toasts UI must load on every page — per-page callsites (login flow, etc.) still use window.toast().",
        )
    }

    @Test
    fun `authenticated head fragment includes the user-authenticated meta tag`() {
        val html = renderHead(username = "alice")
        assertTrue(
            html.contains("name=\"user-authenticated\""),
            "Auth meta must signal the SSE script that subscribing is worth it.",
        )
        assertTrue(
            html.contains("content=\"true\""),
            "Meta content must be exactly `true` — the script does a strict comparison.",
        )
    }

    @Test
    fun `authenticated head fragment includes both toasts and notifications-stream scripts`() {
        val html = renderHead(username = "alice")
        assertTrue(html.contains("/js/toasts.js\""), "Toasts UI must load when authenticated.")
        assertTrue(
            html.contains("/js/notifications-stream.js\""),
            "SSE bridge must load when authenticated.",
        )
    }

    @Test
    fun `headSeo fragment mirrors the head fragment's notification wiring when authenticated`() {
        val html = renderHeadSeo(username = "alice")
        assertTrue(html.contains("name=\"user-authenticated\""))
        assertTrue(html.contains("/js/toasts.js\""))
        assertTrue(html.contains("/js/notifications-stream.js\""))
    }

    @Test
    fun `headSeo fragment omits SSE wiring when anonymous`() {
        val html = renderHeadSeo(username = null)
        assertFalse(html.contains("name=\"user-authenticated\""))
        assertFalse(html.contains("/js/notifications-stream.js\""))
        assertTrue(html.contains("/js/toasts.js\""))
    }
}
