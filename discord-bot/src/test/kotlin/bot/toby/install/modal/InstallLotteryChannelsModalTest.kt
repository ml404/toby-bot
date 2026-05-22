package bot.toby.install.modal

import core.modal.ModalContext
import database.dto.ConfigDto.Configurations
import database.service.ConfigService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.interactions.modals.ModalMapping
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class InstallLotteryChannelsModalTest {

    private lateinit var configService: ConfigService
    private lateinit var modal: InstallLotteryChannelsModal
    private lateinit var event: ModalInteractionEvent
    private lateinit var ctx: ModalContext
    private lateinit var guild: Guild

    @BeforeEach
    fun setUp() {
        configService = mockk(relaxed = true)
        modal = InstallLotteryChannelsModal(configService)
        event = mockk(relaxed = true)
        guild = mockk(relaxed = true) { every { id } returns "g1" }
        ctx = mockk {
            every { this@mockk.event } returns this@InstallLotteryChannelsModalTest.event
            every { this@mockk.guild } returns this@InstallLotteryChannelsModalTest.guild
        }
        every { event.reply(any<String>()) } returns mockk(relaxed = true) {
            every { setEphemeral(any()) } returns this
            every { queue() } just Runs
        }
        every { event.getValue(any<String>()) } returns null
    }

    private fun stubChannelPick(channelId: Long) {
        every { event.getValue(InstallLotteryChannelsModal.FIELD_ANNOUNCE) } returns mockk<ModalMapping> {
            every { asLongList } returns listOf(channelId)
        }
    }

    @Test
    fun `name and modal id constants align`() {
        assertEquals(InstallLotteryChannelsModal.MODAL_NAME, modal.name)
        assertEquals("install_lottery_channels", InstallLotteryChannelsModal.MODAL_NAME)
    }

    @Test
    fun `buildModal returns a JDA modal with the right id`() {
        val built = modal.buildModal()
        assertEquals(InstallLotteryChannelsModal.MODAL_NAME, built.id)
        assertNotNull(built.title)
    }

    @Test
    fun `no selection replies nothing changed and writes nothing`() {
        modal.handle(ctx, 0)

        verify(exactly = 1) { event.reply(match<String> { it.contains("nothing changed", ignoreCase = true) }) }
        verify(exactly = 0) { configService.upsertAll(any(), any()) }
        verify(exactly = 0) {
            configService.upsertConfig(any<String>(), any<String>(), any<String>())
        }
    }

    @Test
    fun `picked channel writes LOTTERY_CHANNEL via batch upsert`() {
        val textChannel = mockk<TextChannel> { every { name } returns "lottery-announce" }
        stubChannelPick(54321L)
        every { guild.getTextChannelById(54321L) } returns textChannel
        val rowsSlot = slot<List<Pair<String, String>>>()
        every { configService.upsertAll("g1", capture(rowsSlot)) } returns emptyList()

        modal.handle(ctx, 0)

        verify(exactly = 1) { configService.upsertAll("g1", any()) }
        assertEquals(
            listOf(Configurations.LOTTERY_CHANNEL.configValue to "54321"),
            rowsSlot.captured,
        )
    }

    @Test
    fun `picked channel no longer exists rejects without writes`() {
        stubChannelPick(999L)
        every { guild.getTextChannelById(999L) } returns null

        modal.handle(ctx, 0)

        verify(exactly = 1) { event.reply(match<String> { it.contains("no longer exists") }) }
        verify(exactly = 0) { configService.upsertAll(any(), any()) }
        verify(exactly = 0) {
            configService.upsertConfig(any<String>(), any<String>(), any<String>())
        }
    }

    @Test
    fun `successful save replies with summary listing the channel name`() {
        val channel = mockk<TextChannel> { every { name } returns "lottery-announce" }
        stubChannelPick(7L)
        every { guild.getTextChannelById(7L) } returns channel

        modal.handle(ctx, 0)

        verify(exactly = 1) {
            event.reply(match<String> { it.contains("lottery-announce") })
        }
    }
}
