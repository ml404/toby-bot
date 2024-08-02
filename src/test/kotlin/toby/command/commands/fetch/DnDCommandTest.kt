package toby.command.commands.fetch

import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import toby.command.CommandTest
import toby.command.CommandTest.Companion.event
import toby.command.CommandTest.Companion.webhookMessageCreateAction
import toby.command.commands.dnd.DnDCommand
import toby.helpers.DnDHelper
import toby.helpers.HttpHelper

@Disabled
class DnDCommandTest : CommandTest {

    private lateinit var command: DnDCommand
    private lateinit var httpHelper: HttpHelper
    private val deleteDelay = 0

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        httpHelper = mockk(relaxed = true)
        command = DnDCommand()
        every {
            event.hook.sendMessageEmbeds(
                any<MessageEmbed>(),
                *anyVararg()
            )
        } returns webhookMessageCreateAction
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        unmockkAll()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `should handle successful lookup and reply with embed`() = runTest {
        // Mocking JSON responses for doInitialLookup and queryNonMatchRetry
        every { httpHelper.fetchFromGet(any()) } returns fireballJson andThen noQueryFound

        val embedSlot = slot<MessageEmbed>()
        command.handleWithHttpObjects(
            event,
            "spell",
            "spells",
            "fireball",
            httpHelper,
            deleteDelay
        )

       // Ensure all asynchronous code completes
        advanceUntilIdle()

        // Verify interactions and responses
        coVerify {
            DnDHelper.doInitialLookup("spell", "spells", "fireball", httpHelper)
            DnDHelper.queryNonMatchRetry("spells", "fireball", httpHelper)
            event.hook.sendMessageEmbeds(capture(embedSlot))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `should handle no initial results but successful followup scenario`() = runTest {
        // Mocking JSON responses for doInitialLookup and queryNonMatchRetry

        every { httpHelper.fetchFromGet(any()) } returns noQueryFound andThen blindJson

        command.handleWithHttpObjects(
            event,
            "condition",
            "conditions",
            "blind",
            httpHelper,
            deleteDelay
        )

        // Verify interactions and responses
        coVerify {
            DnDHelper.doInitialLookup("condition", "conditions", "blind", httpHelper)
            DnDHelper.queryNonMatchRetry("conditions", "blind", httpHelper)
            event.hook.sendMessage(any<String>()).setActionRow(any<StringSelectMenu>()).queue()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `should handle no results scenario`() = runTest {
        // Mocking JSON responses for doInitialLookup and queryNonMatchRetry
        every { httpHelper.fetchFromGet(any()) } returns noQueryFound andThen noQueryFound

        command.handleWithHttpObjects(
            event,
            "condition",
            "conditions",
            "bli",
            httpHelper,
            deleteDelay
        )

        // Verify interactions and responses
        coVerify {
            DnDHelper.doInitialLookup("condition", "conditions", "bli", httpHelper)
            DnDHelper.queryNonMatchRetry("conditions", "bli", httpHelper)
            event.hook.sendMessage(any<String>()).queue()
        }
    }

    companion object {
        const val fireballJson =
            """{"index":"fireball","name":"Fireball","desc":["A bright streak flashes from your pointing finger to a point you choose within range and then blossoms with a low roar into an explosion of flame. Each creature in a 20-foot-radius sphere centered on that point must make a dexterity saving throw. A target takes 8d6 fire damage on a failed save, or half as much damage on a successful one.","The fire spreads around corners. It ignites flammable objects in the area that aren't being worn or carried."],"higher_level":["When you cast this spell using a spell slot of 4th level or higher, the damage increases by 1d6 for each slot level above 3rd."],"range":"150 feet","components":["V","S","M"],"material":"A tiny ball of bat guano and sulfur.","ritual":false,"duration":"Instantaneous","concentration":false,"casting_time":"1 action","level":3,"damage":{"damage_type":{"index":"fire","name":"Fire","url":"/api/damage-types/fire"},"damage_at_slot_level":{"3":"8d6","4":"9d6","5":"10d6","6":"11d6","7":"12d6","8":"13d6","9":"14d6"}},"dc":{"dc_type":{"index":"dex","name":"DEX","url":"/api/ability-scores/dex"},"dc_success":"half"},"area_of_effect":{"type":"sphere","size":20},"school":{"index":"evocation","name":"Evocation","url":"/api/magic-schools/evocation"},"classes":[{"index":"sorcerer","name":"Sorcerer","url":"/api/classes/sorcerer"},{"index":"wizard","name":"Wizard","url":"/api/classes/wizard"}],"subclasses":[{"index":"lore","name":"Lore","url":"/api/subclasses/lore"},{"index":"fiend","name":"Fiend","url":"/api/subclasses/fiend"}],"url":"/api/spells/fireball"}"""
        const val blindJson =
            """{"count":1,"results":[{"index":"blinded","name":"Blinded","url":"/api/conditions/blinded"}]}"""
        const val noQueryFound = """{"error":"Not found"}"""
    }
}

