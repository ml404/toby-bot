package bot.toby.modal.modals.setconfig

import bot.toby.activity.ActivityTrackingNotifier
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
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.modals.ModalMapping
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SetConfigActivityModalTest {

    private lateinit var configService: ConfigService
    private lateinit var notifier: ActivityTrackingNotifier
    private lateinit var modal: SetConfigActivityModal
    private lateinit var ctx: ModalContext
    private lateinit var event: ModalInteractionEvent
    private lateinit var hook: InteractionHook
    private lateinit var guild: Guild
    private val messageSlot = slot<String>()
    private val guildId = "100"

    @BeforeEach
    fun setUp() {
        configService = mockk(relaxed = true)
        notifier = mockk(relaxed = true)
        modal = SetConfigActivityModal(configService, notifier)
        hook = mockk(relaxed = true)
        event = mockk(relaxed = true)
        guild = mockk(relaxed = true) {
            every { id } returns guildId
        }
        every { event.hook } returns hook
        every { event.modalId } returns SetConfigActivityModal.MODAL_NAME
        ctx = mockk(relaxed = true)
        every { ctx.event } returns event
        every { ctx.guild } returns guild
        @Suppress("UNCHECKED_CAST")
        val send = mockk<WebhookMessageCreateAction<Message>>(relaxed = true)
        every { hook.sendMessage(capture(messageSlot)) } returns send
        every { send.setEphemeral(true) } returns send
        every { send.queue() } just Runs

        // Default: no fields submitted
        every { event.getValue(any()) } returns null
    }

    @Test
    fun `first-enable from previously-disabled fires the notifier`() {
        stub(SetConfigActivityModal.FIELD_ACTIVITY_TRACKING, "true")
        every { configService.upsertAll(guildId, any()) } returns listOf(
            ConfigService.UpsertResult.Updated(
                ConfigDto(Configurations.ACTIVITY_TRACKING.configValue, "true", guildId),
                previousValue = "false",
            ),
        )

        modal.handle(ctx, 0)

        verify(exactly = 1) { notifier.notifyMembersOnFirstEnable(guild) }
    }

    @Test
    fun `first-enable from never-set (Created) also fires the notifier`() {
        stub(SetConfigActivityModal.FIELD_ACTIVITY_TRACKING, "true")
        every { configService.upsertAll(guildId, any()) } returns listOf(
            ConfigService.UpsertResult.Created(
                ConfigDto(Configurations.ACTIVITY_TRACKING.configValue, "true", guildId),
            ),
        )

        modal.handle(ctx, 0)

        verify(exactly = 1) { notifier.notifyMembersOnFirstEnable(guild) }
    }

    @Test
    fun `re-enable when previously enabled does NOT re-fire the notifier`() {
        stub(SetConfigActivityModal.FIELD_ACTIVITY_TRACKING, "true")
        every { configService.upsertAll(guildId, any()) } returns listOf(
            ConfigService.UpsertResult.Updated(
                ConfigDto(Configurations.ACTIVITY_TRACKING.configValue, "true", guildId),
                previousValue = "true",
            ),
        )

        modal.handle(ctx, 0)

        verify(exactly = 0) { notifier.notifyMembersOnFirstEnable(any()) }
    }

    @Test
    fun `disabling never fires the notifier`() {
        stub(SetConfigActivityModal.FIELD_ACTIVITY_TRACKING, "false")
        every { configService.upsertAll(guildId, any()) } returns listOf(
            ConfigService.UpsertResult.Updated(
                ConfigDto(Configurations.ACTIVITY_TRACKING.configValue, "false", guildId),
                previousValue = "true",
            ),
        )

        modal.handle(ctx, 0)

        verify(exactly = 0) { notifier.notifyMembersOnFirstEnable(any()) }
    }

    @Test
    fun `blank tracking field skips both the upsert and the notifier`() {
        // Default: all event.getValue calls return null → Skip outcome
        modal.handle(ctx, 0)

        verify(exactly = 0) { configService.upsertAll(any(), any()) }
        verify(exactly = 0) { configService.upsertConfig(any(), any(), any()) }
        verify(exactly = 0) { notifier.notifyMembersOnFirstEnable(any()) }
        assertTrue(messageSlot.captured.contains("No fields filled"))
    }

    @Test
    fun `numeric fields parse and upsert in order`() {
        stub(SetConfigActivityModal.FIELD_UBI_DAILY_AMOUNT, "50")
        stub(SetConfigActivityModal.FIELD_DAILY_CREDIT_CAP, "200")
        val rowsSlot = slot<List<Pair<String, String>>>()
        every { configService.upsertAll(guildId, capture(rowsSlot)) } returns listOf(
            ConfigService.UpsertResult.Created(
                ConfigDto(Configurations.UBI_DAILY_AMOUNT.configValue, "50", guildId),
            ),
            ConfigService.UpsertResult.Created(
                ConfigDto(Configurations.DAILY_CREDIT_CAP.configValue, "200", guildId),
            ),
        )

        modal.handle(ctx, 0)

        verify(exactly = 1) { configService.upsertAll(guildId, any()) }
        // Order matches the modal's spec map iteration; UBI comes before cap.
        assertTrue(
            rowsSlot.captured.contains(Configurations.UBI_DAILY_AMOUNT.configValue to "50") &&
                rowsSlot.captured.contains(Configurations.DAILY_CREDIT_CAP.configValue to "200")
        )
        verify(exactly = 0) { notifier.notifyMembersOnFirstEnable(any()) }
    }

    @Test
    fun `out-of-range UBI rejects with errors message and writes nothing`() {
        stub(SetConfigActivityModal.FIELD_UBI_DAILY_AMOUNT, "99999")

        modal.handle(ctx, 0)

        verify(exactly = 0) { configService.upsertAll(any(), any()) }
        verify(exactly = 0) { configService.upsertConfig(any(), any(), any()) }
        assertTrue(messageSlot.captured.contains("Couldn't save"))
        assertTrue(messageSlot.captured.contains("UBI daily amount"))
    }

    private fun stub(field: String, value: String) {
        val mapping = mockk<ModalMapping> { every { asString } returns value }
        every { event.getValue(field) } returns mapping
    }
}
