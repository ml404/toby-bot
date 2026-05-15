package bot.toby.helpers

import bot.toby.command.commands.dnd.DnDSearchCommand.Companion.CONDITION_NAME
import bot.toby.dto.web.dnd.Condition
import bot.toby.dto.web.dnd.Feature
import bot.toby.dto.web.dnd.Rule
import bot.toby.dto.web.dnd.Spell
import bot.toby.menu.menus.dnd.DndMenu.Companion.FEATURE_NAME
import bot.toby.menu.menus.dnd.DndMenu.Companion.RULE_NAME
import bot.toby.menu.menus.dnd.DndMenu.Companion.SPELL_NAME
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class DnDHelperTest {
    private lateinit var dndHelper: DnDHelper

    @BeforeEach
    fun setUp() {
        dndHelper = DnDHelper()
    }

    @Test
    fun testRollDice() {
        val result = dndHelper.rollDice(20, 2)
        Assertions.assertTrue(result in 2..40, "Result should be between 2 and 40 (inclusive)")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testDoInitialLookupWithSpell() = runTest {
        val mockResponse = """{"name": "Fireball"}"""
        val httpHelper = mockk<HttpHelper>()
        coEvery { httpHelper.fetchFromGet(any()) } returns mockResponse

        val response = dndHelper.doInitialLookup(SPELL_NAME, "spell", "fireball", httpHelper)
        Assertions.assertTrue(response is Spell, "Response should be of type Spell")
        Assertions.assertEquals("Fireball", (response as Spell).name, "Spell name should be 'Fireball'")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testDoInitialLookupWithCondition() = runTest {
        val mockResponse = """{"name": "Blinded"}"""
        val httpHelper = mockk<HttpHelper>()
        coEvery { httpHelper.fetchFromGet(any()) } returns mockResponse

        val response = dndHelper.doInitialLookup(CONDITION_NAME, "condition", "blinded", httpHelper)
        Assertions.assertTrue(response is Condition, "Response should be of type Condition")
        Assertions.assertEquals("Blinded", (response as Condition).name, "Condition name should be 'Blinded'")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testDoInitialLookupWithRule() = runTest {
        val mockResponse = """{"name": "Cover"}"""
        val httpHelper = mockk<HttpHelper>()
        coEvery { httpHelper.fetchFromGet(any()) } returns mockResponse

        val response = dndHelper.doInitialLookup(RULE_NAME, "rule", "cover", httpHelper)
        Assertions.assertTrue(response is Rule, "Response should be of type Rule")
        Assertions.assertEquals("Cover", (response as Rule).name, "Rule name should be 'Cover'")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testDoInitialLookupWithFeature() = runTest {
        val mockResponse = """{"name": "Darkvision"}"""
        val httpHelper = mockk<HttpHelper>()
        coEvery { httpHelper.fetchFromGet(any()) } returns mockResponse

        val response = dndHelper.doInitialLookup(FEATURE_NAME, "feature", "darkvision", httpHelper)
        Assertions.assertTrue(response is Feature, "Response should be of type Feature")
        Assertions.assertEquals("Darkvision", (response as Feature).name, "Feature name should be 'Darkvision'")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testQueryNonMatchRetryWithSpell() = runTest {
        val mockResponse = """{"results": [{"name": "Fireball"}]}"""
        val httpHelper = mockk<HttpHelper>()
        coEvery { httpHelper.fetchFromGet(any()) } returns mockResponse

        val result = dndHelper.queryNonMatchRetry("spell", "fireball", httpHelper)
        Assertions.assertNotNull(result, "Query result should not be null")
        Assertions.assertEquals(
            "Fireball",
            result?.results?.firstOrNull()?.name,
            "Query result name should be 'Fireball'"
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testQueryNonMatchRetryWithRule() = runTest {
        val mockResponse = """{"results": [{"name": "Cover"}]}"""
        val httpHelper = mockk<HttpHelper>()
        coEvery { httpHelper.fetchFromGet(any()) } returns mockResponse

        val result = dndHelper.queryNonMatchRetry("rule", "cover", httpHelper)
        Assertions.assertNotNull(result, "Query result should not be null")
        Assertions.assertEquals("Cover", result?.results?.firstOrNull()?.name, "Query result name should be 'Cover'")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testQueryNonMatchRetryWithFeature() = runTest {
        val mockResponse = """{"results": [{"name": "Darkvision"}]}"""
        val httpHelper = mockk<HttpHelper>()
        coEvery { httpHelper.fetchFromGet(any()) } returns mockResponse

        val result = dndHelper.queryNonMatchRetry("feature", "darkvision", httpHelper)
        Assertions.assertNotNull(result, "Query result should not be null")
        Assertions.assertEquals(
            "Darkvision",
            result?.results?.firstOrNull()?.name,
            "Query result name should be 'Darkvision'"
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testQueryNonMatchRetryWithCondition() = runTest {
        val mockResponse = """{"results": [{"name": "Blinded"}]}"""
        val httpHelper = mockk<HttpHelper>()
        coEvery { httpHelper.fetchFromGet(any()) } returns mockResponse

        val result = dndHelper.queryNonMatchRetry("condition", "blinded", httpHelper)
        Assertions.assertNotNull(result, "Query result should not be null")
        Assertions.assertEquals(
            "Blinded",
            result?.results?.firstOrNull()?.name,
            "Query result name should be 'Blinded'"
        )
    }
}
