package common.mtg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MtgSearchQueryTest {

    @Test
    fun `quotes the name as a phrase and ANDs each type`() {
        assertEquals(
            "\"iron man\" t:legendary t:creature c:r",
            MtgSearchQuery.build("iron man", "legendary creature", "c:r"),
        )
    }

    @Test
    fun `each part is optional`() {
        assertEquals("\"iron man\"", MtgSearchQuery.build("iron man", null, null))
        assertEquals("t:dragon", MtgSearchQuery.build(null, "dragon", null))
        assertEquals("mv<=2", MtgSearchQuery.build(" ", "", "mv<=2"))
    }

    @Test
    fun `all-blank yields an empty query`() {
        assertEquals("", MtgSearchQuery.build(null, null, null))
        assertEquals("", MtgSearchQuery.build("  ", "\t", ""))
    }

    @Test
    fun `strips embedded quotes from the name so the phrase stays well-formed`() {
        assertEquals("\"iron man\"", MtgSearchQuery.build("iron \"man\"", null, null))
    }
}
