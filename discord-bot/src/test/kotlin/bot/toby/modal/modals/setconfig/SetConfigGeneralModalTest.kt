package bot.toby.modal.modals.setconfig

import core.modal.ModalContext
import database.dto.ConfigDto
import database.dto.ConfigDto.Configurations
import database.service.guild.ConfigService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.modals.ModalMapping
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SetConfigGeneralModalTest {

    private lateinit var configService: ConfigService
    private lateinit var modal: SetConfigGeneralModal
    private lateinit var ctx: ModalContext
    private lateinit var event: ModalInteractionEvent
    private lateinit var hook: InteractionHook
    private lateinit var guild: Guild
    private val messageSlot = slot<String>()
    private val guildId = "100"

    @BeforeEach
    fun setUp() {
        configService = mockk(relaxed = true)
        modal = SetConfigGeneralModal(configService)
        hook = mockk(relaxed = true)
        event = mockk(relaxed = true)
        guild = mockk(relaxed = true) {
            every { id } returns guildId
        }
        every { event.hook } returns hook
        every { event.modalId } returns SetConfigGeneralModal.MODAL_NAME
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

    @Test
    fun `happy path writes the filled fields`() {
        stub(SetConfigGeneralModal.FIELD_VOLUME, "120")
        stub(SetConfigGeneralModal.FIELD_DELETE_DELAY, "10")
        val rowsSlot = slot<List<Pair<String, String>>>()
        every { configService.upsertAll(guildId, capture(rowsSlot)) } returns listOf(
            ConfigService.UpsertResult.Created(ConfigDto(Configurations.VOLUME.configValue, "120", guildId)),
            ConfigService.UpsertResult.Created(ConfigDto(Configurations.DELETE_DELAY.configValue, "10", guildId)),
        )

        modal.handle(ctx, 0)

        verify(exactly = 1) { configService.upsertAll(guildId, any()) }
        assertTrue(rowsSlot.captured.contains(Configurations.VOLUME.configValue to "120"))
        assertTrue(rowsSlot.captured.contains(Configurations.DELETE_DELAY.configValue to "10"))
        assertTrue(messageSlot.captured.startsWith("Saved 2 settings"))
    }

    @Test
    fun `all-blank submission is a no-op with a friendly reply`() {
        modal.handle(ctx, 0)

        verify(exactly = 0) { configService.upsertAll(any(), any()) }
        verify(exactly = 0) { configService.upsertConfig(any(), any(), any()) }
        assertTrue(messageSlot.captured.contains("No fields filled"))
    }

    @Test
    fun `MOVE resolves the channel name from a numeric id`() {
        stub(SetConfigGeneralModal.FIELD_MOVE, "42")
        val channel = mockk<TextChannel> { every { name } returns "general" }
        every { guild.getTextChannelById(42L) } returns channel
        val rowsSlot = slot<List<Pair<String, String>>>()
        every { configService.upsertAll(guildId, capture(rowsSlot)) } returns listOf(
            ConfigService.UpsertResult.Created(ConfigDto(Configurations.MOVE.configValue, "general", guildId)),
        )

        modal.handle(ctx, 0)

        // Must persist the channel NAME (legacy MoveCommand contract), not the id.
        verify(exactly = 1) { configService.upsertAll(guildId, any()) }
        assertEquals(listOf(Configurations.MOVE.configValue to "general"), rowsSlot.captured)
    }

    @Test
    fun `MOVE with unknown id fails with an error and writes nothing`() {
        stub(SetConfigGeneralModal.FIELD_MOVE, "999")
        every { guild.getTextChannelById(999L) } returns null
        every { guild.getVoiceChannelById(999L) } returns null

        modal.handle(ctx, 0)

        verify(exactly = 0) { configService.upsertAll(any(), any()) }
        verify(exactly = 0) { configService.upsertConfig(any(), any(), any()) }
        assertTrue(messageSlot.captured.contains("Couldn't save"))
    }

    @Test
    fun `validation errors are collected and no writes happen`() {
        stub(SetConfigGeneralModal.FIELD_VOLUME, "9999")
        stub(SetConfigGeneralModal.FIELD_DELETE_DELAY, "100")
        every {
            configService.upsertConfig(Configurations.DELETE_DELAY.configValue, "100", guildId)
        } returns ConfigService.UpsertResult.Created(ConfigDto(Configurations.DELETE_DELAY.configValue, "100", guildId))

        modal.handle(ctx, 0)

        // Even though DELETE_DELAY is valid, the bad VOLUME aborts the whole batch.
        verify(exactly = 0) { configService.upsertConfig(any(), any(), any()) }
        assertTrue(messageSlot.captured.contains("Default volume"))
    }

    private fun stub(field: String, value: String) {
        val mapping = mockk<ModalMapping> { every { asString } returns value }
        every { event.getValue(field) } returns mapping
    }
}
