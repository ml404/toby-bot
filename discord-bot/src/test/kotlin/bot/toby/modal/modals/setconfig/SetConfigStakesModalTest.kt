package bot.toby.modal.modals.setconfig

import core.modal.ModalContext
import database.dto.guild.ConfigDto
import database.dto.guild.ConfigDto.Configurations
import database.service.guild.ConfigService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.modals.ModalMapping
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SetConfigStakesModalTest {

    private lateinit var configService: ConfigService
    private lateinit var modal: SetConfigStakesModal
    private lateinit var ctx: ModalContext
    private lateinit var event: ModalInteractionEvent
    private lateinit var hook: InteractionHook
    private lateinit var guild: Guild
    private val messageSlot = slot<String>()
    private val guildId = "100"

    @BeforeEach
    fun setUp() {
        configService = mockk(relaxed = true)
        modal = SetConfigStakesModal(configService)
        hook = mockk(relaxed = true)
        event = mockk(relaxed = true)
        guild = mockk(relaxed = true) {
            every { id } returns guildId
        }
        every { event.hook } returns hook
        ctx = mockk(relaxed = true)
        every { ctx.event } returns event
        every { ctx.guild } returns guild
        @Suppress("UNCHECKED_CAST")
        val send = mockk<WebhookMessageCreateAction<Message>>(relaxed = true)
        every { hook.sendMessage(capture(messageSlot)) } returns send
        every { send.setEphemeral(true) } returns send
        every { send.queue() } just Runs
        every { event.getValue(any()) } returns null
    }

    // ---- customId encoding/decoding ----

    @Test
    fun `customIdFor builds the modal id with the game token`() {
        assertEquals("setconfig_stakes:dice", SetConfigStakesModal.customIdFor(SetConfigStakesModal.Game.DICE))
        assertEquals("setconfig_stakes:duel", SetConfigStakesModal.customIdFor(SetConfigStakesModal.Game.DUEL))
    }

    @Test
    fun `Game byToken is case-insensitive and rejects unknown values`() {
        assertEquals(SetConfigStakesModal.Game.HOLDEM, SetConfigStakesModal.Game.byToken("HOLDEM"))
        assertEquals(SetConfigStakesModal.Game.HOLDEM, SetConfigStakesModal.Game.byToken("holdem"))
        assertEquals(null, SetConfigStakesModal.Game.byToken("blackjack"))
    }

    // ---- per-game behaviour ----

    @Test
    fun `dice modal writes DICE_MIN_STAKE, DICE_MAX_STAKE and DICE_BOT_EDGE`() {
        every { event.modalId } returns SetConfigStakesModal.customIdFor(SetConfigStakesModal.Game.DICE)
        stub(SetConfigStakesModal.FIELD_MIN_STAKE, "5")
        stub(SetConfigStakesModal.FIELD_MAX_STAKE, "500")
        stub(SetConfigStakesModal.FIELD_BOT_EDGE_PCT, "30")
        val rowsSlot = stubUpsertAllReturningCreated()

        modal.handle(ctx, 0)

        verify(exactly = 1) { configService.upsertAll(guildId, any()) }
        assertTrue(rowsSlot.captured.contains(Configurations.DICE_MIN_STAKE.configValue to "5"))
        assertTrue(rowsSlot.captured.contains(Configurations.DICE_MAX_STAKE.configValue to "500"))
        assertTrue(rowsSlot.captured.contains(Configurations.DICE_BOT_EDGE_MAX_PCT.configValue to "30"))
    }

    @Test
    fun `duel modal has no bot-edge field - fills only MIN and MAX`() {
        every { event.modalId } returns SetConfigStakesModal.customIdFor(SetConfigStakesModal.Game.DUEL)
        stub(SetConfigStakesModal.FIELD_MIN_STAKE, "10")
        stub(SetConfigStakesModal.FIELD_MAX_STAKE, "1000")
        stub(SetConfigStakesModal.FIELD_BOT_EDGE_PCT, "30") // ignored — not in specs
        val rowsSlot = stubUpsertAllReturningCreated()

        modal.handle(ctx, 0)

        verify(exactly = 1) { configService.upsertAll(guildId, any()) }
        assertTrue(rowsSlot.captured.contains(Configurations.DUEL_MIN_STAKE.configValue to "10"))
        assertTrue(rowsSlot.captured.contains(Configurations.DUEL_MAX_STAKE.configValue to "1000"))
        // No bot-edge key exists for DUEL → never written even though the field
        // was submitted. The modal's specs map is the source of truth.
        assertTrue(rowsSlot.captured.none { it.first.contains("BOT_EDGE") })
    }

    @Test
    fun `blank min stake skips that one write, max still goes through`() {
        every { event.modalId } returns SetConfigStakesModal.customIdFor(SetConfigStakesModal.Game.SLOTS)
        // FIELD_MIN_STAKE remains null (blank)
        stub(SetConfigStakesModal.FIELD_MAX_STAKE, "750")
        val rowsSlot = stubUpsertAllReturningCreated()

        modal.handle(ctx, 0)

        verify(exactly = 1) { configService.upsertAll(guildId, any()) }
        assertTrue(rowsSlot.captured.none { it.first == Configurations.SLOTS_MIN_STAKE.configValue })
        assertTrue(rowsSlot.captured.contains(Configurations.SLOTS_MAX_STAKE.configValue to "750"))
    }

    @Test
    fun `non-numeric min rejects without writing anything`() {
        every { event.modalId } returns SetConfigStakesModal.customIdFor(SetConfigStakesModal.Game.KENO)
        stub(SetConfigStakesModal.FIELD_MIN_STAKE, "abc")
        stub(SetConfigStakesModal.FIELD_MAX_STAKE, "500")

        modal.handle(ctx, 0)

        verify(exactly = 0) { configService.upsertAll(any(), any()) }
        verify(exactly = 0) { configService.upsertConfig(any(), any(), any()) }
        assertTrue(messageSlot.captured.contains("Couldn't save"))
    }

    private fun stub(field: String, value: String) {
        val mapping = mockk<ModalMapping> { every { asString } returns value }
        every { event.getValue(field) } returns mapping
    }

    /**
     * Stub `upsertAll` to capture the input list and return per-row
     * `Created` results synthesized from the captured rows. Saves the
     * test from re-asserting the Created/Updated distinction (covered
     * by [DefaultConfigServiceUpsertAllTest]) and lets the modal's
     * `afterWrites` hook see a sensible result.
     */
    private fun stubUpsertAllReturningCreated(): io.mockk.CapturingSlot<List<Pair<String, String>>> {
        val slot = io.mockk.slot<List<Pair<String, String>>>()
        every { configService.upsertAll(guildId, capture(slot)) } answers {
            slot.captured.map { (name, value) ->
                ConfigService.UpsertResult.Created(ConfigDto(name, value, guildId))
            }
        }
        return slot
    }
}
