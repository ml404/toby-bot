package web.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BotOwnerAuthorizerTest {

    @Test
    fun `parses comma-separated ids tolerating surrounding whitespace`() {
        val authorizer = BotOwnerAuthorizer("123, 456 ,789")
        assertTrue(authorizer.isBotOwner(123L))
        assertTrue(authorizer.isBotOwner(456L))
        assertTrue(authorizer.isBotOwner(789L))
    }

    @Test
    fun `blank env denies everyone and reports no owners`() {
        val authorizer = BotOwnerAuthorizer("")
        assertFalse(authorizer.hasAnyOwner)
        assertFalse(authorizer.isBotOwner(123L))
    }

    @Test
    fun `non-numeric tokens are skipped but valid ones still parse`() {
        val authorizer = BotOwnerAuthorizer("abc,123,")
        assertTrue(authorizer.hasAnyOwner)
        assertTrue(authorizer.isBotOwner(123L))
        assertFalse(authorizer.isBotOwner(0L))
    }

    @Test
    fun `null discord id is denied`() {
        val authorizer = BotOwnerAuthorizer("123")
        assertFalse(authorizer.isBotOwner(null))
    }

    @Test
    fun `id outside the configured set is denied`() {
        val authorizer = BotOwnerAuthorizer("123")
        assertFalse(authorizer.isBotOwner(999L))
    }
}
