package bot.toby.install.modal

import bot.toby.modal.modals.setconfig.SetConfigStakesModal
import core.modal.ModalContext
import database.dto.ConfigDto
import database.dto.ConfigDto.Configurations
import database.service.ConfigService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.interactions.modals.ModalMapping
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class InstallAllStakesModalTest {

    private lateinit var configService: ConfigService
    private lateinit var modal: InstallAllStakesModal
    private lateinit var event: ModalInteractionEvent
    private lateinit var ctx: ModalContext
    private lateinit var guild: Guild

    @BeforeEach
    fun setUp() {
        configService = mockk(relaxed = true)
        modal = InstallAllStakesModal(configService)
        event = mockk(relaxed = true)
        guild = mockk(relaxed = true) { every { id } returns "g1" }
        ctx = mockk {
            every { this@mockk.event } returns this@InstallAllStakesModalTest.event
            every { this@mockk.guild } returns this@InstallAllStakesModalTest.guild
        }
        every { event.reply(any<String>()) } returns mockk(relaxed = true) {
            every { setEphemeral(any()) } returns this
            every { queue() } just Runs
        }
    }

    private fun stubField(id: String, value: String?) {
        every { event.getValue(id) } returns value?.let {
            mockk<ModalMapping> { every { asString } returns it }
        }
    }

    @Test
    fun `name and modal id constants match`() {
        assertEquals(InstallAllStakesModal.MODAL_NAME, modal.name)
        assertEquals("install_all_stakes", InstallAllStakesModal.MODAL_NAME)
    }

    @Test
    fun `buildModal returns a JDA modal with the right id`() {
        val built = modal.buildModal("100", "1000")
        assertEquals(InstallAllStakesModal.MODAL_NAME, built.id)
        assertNotNull(built.title)
    }

    @Test
    fun `empty min and max replies nothing changed without writes`() {
        stubField(InstallAllStakesModal.FIELD_MIN_STAKE, "")
        stubField(InstallAllStakesModal.FIELD_MAX_STAKE, "")

        modal.handle(ctx, 0)

        verify(exactly = 1) { event.reply(any<String>()) }
        verify(exactly = 0) { configService.upsertAll(any(), any()) }
        verify(exactly = 0) {
            configService.upsertConfig(any<String>(), any<String>(), any<String>())
        }
    }

    @Test
    fun `invalid min replies error without writes`() {
        stubField(InstallAllStakesModal.FIELD_MIN_STAKE, "abc")
        stubField(InstallAllStakesModal.FIELD_MAX_STAKE, "1000")

        modal.handle(ctx, 0)

        verify(exactly = 1) { event.reply(match<String> { it.contains("Min") }) }
        verify(exactly = 0) { configService.upsertAll(any(), any()) }
        verify(exactly = 0) {
            configService.upsertConfig(any<String>(), any<String>(), any<String>())
        }
    }

    @Test
    fun `min greater than max is rejected with no writes`() {
        stubField(InstallAllStakesModal.FIELD_MIN_STAKE, "5000")
        stubField(InstallAllStakesModal.FIELD_MAX_STAKE, "100")

        modal.handle(ctx, 0)

        verify(exactly = 1) { event.reply(match<String> { it.contains("exceed") }) }
        verify(exactly = 0) { configService.upsertAll(any(), any()) }
        verify(exactly = 0) {
            configService.upsertConfig(any<String>(), any<String>(), any<String>())
        }
    }

    @Test
    fun `min zero is rejected as invalid`() {
        stubField(InstallAllStakesModal.FIELD_MIN_STAKE, "0")
        stubField(InstallAllStakesModal.FIELD_MAX_STAKE, "")

        modal.handle(ctx, 0)

        verify(exactly = 1) { event.reply(any<String>()) }
        verify(exactly = 0) { configService.upsertAll(any(), any()) }
        verify(exactly = 0) {
            configService.upsertConfig(any<String>(), any<String>(), any<String>())
        }
    }

    @Test
    fun `min only writes one batched upsert containing MIN_STAKE for every game`() {
        stubField(InstallAllStakesModal.FIELD_MIN_STAKE, "100")
        stubField(InstallAllStakesModal.FIELD_MAX_STAKE, "")
        val rowsSlot = slot<List<Pair<String, String>>>()
        every { configService.upsertAll("g1", capture(rowsSlot)) } returns emptyList()

        modal.handle(ctx, 0)

        verify(exactly = 1) { configService.upsertAll("g1", any()) }
        val gameCount = SetConfigStakesModal.Game.entries.size
        assertEquals(gameCount, rowsSlot.captured.size)
        SetConfigStakesModal.Game.entries.forEach { game ->
            assertTrue(rowsSlot.captured.contains(game.minKey.configValue to "100"))
            assertFalse(rowsSlot.captured.any { it.first == game.maxKey.configValue })
        }
    }

    @Test
    fun `max only writes one batched upsert containing MAX_STAKE for every game`() {
        stubField(InstallAllStakesModal.FIELD_MIN_STAKE, "")
        stubField(InstallAllStakesModal.FIELD_MAX_STAKE, "5000")
        val rowsSlot = slot<List<Pair<String, String>>>()
        every { configService.upsertAll("g1", capture(rowsSlot)) } returns emptyList()

        modal.handle(ctx, 0)

        verify(exactly = 1) { configService.upsertAll("g1", any()) }
        val gameCount = SetConfigStakesModal.Game.entries.size
        assertEquals(gameCount, rowsSlot.captured.size)
        SetConfigStakesModal.Game.entries.forEach { game ->
            assertTrue(rowsSlot.captured.contains(game.maxKey.configValue to "5000"))
            assertFalse(rowsSlot.captured.any { it.first == game.minKey.configValue })
        }
    }

    @Test
    fun `both min and max writes one batched upsert with both keys for every game`() {
        stubField(InstallAllStakesModal.FIELD_MIN_STAKE, "100")
        stubField(InstallAllStakesModal.FIELD_MAX_STAKE, "5000")
        val rowsSlot = slot<List<Pair<String, String>>>()
        every { configService.upsertAll("g1", capture(rowsSlot)) } returns emptyList()

        modal.handle(ctx, 0)

        verify(exactly = 1) { configService.upsertAll("g1", any()) }
        val gameCount = SetConfigStakesModal.Game.entries.size
        assertEquals(gameCount * 2, rowsSlot.captured.size)
        SetConfigStakesModal.Game.entries.forEach { game ->
            assertTrue(rowsSlot.captured.contains(game.minKey.configValue to "100"))
            assertTrue(rowsSlot.captured.contains(game.maxKey.configValue to "5000"))
        }
    }

    @Test
    fun `min parse failure is distinguished from out-of-range`() {
        // Validation message disambiguates "couldn't parse" vs "parsed too small".
        stubField(InstallAllStakesModal.FIELD_MIN_STAKE, "abc")
        stubField(InstallAllStakesModal.FIELD_MAX_STAKE, "")

        modal.handle(ctx, 0)

        verify(exactly = 1) {
            event.reply(match<String> { it.contains("must be a whole number") })
        }
    }

    @Test
    fun `min equal to zero reports out-of-range`() {
        stubField(InstallAllStakesModal.FIELD_MIN_STAKE, "0")
        stubField(InstallAllStakesModal.FIELD_MAX_STAKE, "")

        modal.handle(ctx, 0)

        verify(exactly = 1) {
            event.reply(match<String> { it.contains("must be ≥ 1") && it.contains("0") })
        }
    }

    @Test
    fun `successful save replies with summary mentioning every game`() {
        stubField(InstallAllStakesModal.FIELD_MIN_STAKE, "10")
        stubField(InstallAllStakesModal.FIELD_MAX_STAKE, "")

        modal.handle(ctx, 0)

        val gameCount = SetConfigStakesModal.Game.entries.size
        verify(exactly = 1) {
            event.reply(match<String> {
                it.contains(gameCount.toString()) && it.contains("Min stake")
            })
        }
    }
}
