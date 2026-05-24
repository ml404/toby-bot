package bot.toby.install.modal

import core.modal.ModalContext
import database.dto.guild.ConfigDto.Configurations
import database.service.guild.ConfigService
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class InstallActivityChannelsModalTest {

    private lateinit var configService: ConfigService
    private lateinit var modal: InstallActivityChannelsModal
    private lateinit var event: ModalInteractionEvent
    private lateinit var ctx: ModalContext
    private lateinit var guild: Guild

    @BeforeEach
    fun setUp() {
        configService = mockk(relaxed = true)
        modal = InstallActivityChannelsModal(configService)
        event = mockk(relaxed = true)
        guild = mockk(relaxed = true) { every { id } returns "g1" }
        ctx = mockk {
            every { this@mockk.event } returns this@InstallActivityChannelsModalTest.event
            every { this@mockk.guild } returns this@InstallActivityChannelsModalTest.guild
        }
        every { event.reply(any<String>()) } returns mockk(relaxed = true) {
            every { setEphemeral(any()) } returns this
            every { queue() } just Runs
        }
        every { event.getValue(any<String>()) } returns null
    }

    private fun stubLevelUpPick(channelId: Long) {
        every { event.getValue(InstallActivityChannelsModal.FIELD_LEVEL_UP) } returns mockk<ModalMapping> {
            every { asLongList } returns listOf(channelId)
        }
    }

    private fun stubAchievementPick(channelId: Long) {
        every { event.getValue(InstallActivityChannelsModal.FIELD_ACHIEVEMENT) } returns mockk<ModalMapping> {
            every { asLongList } returns listOf(channelId)
        }
    }

    @Test
    fun `name and modal id constants align`() {
        assertEquals(InstallActivityChannelsModal.MODAL_NAME, modal.name)
        assertEquals("install_activity_channels", InstallActivityChannelsModal.MODAL_NAME)
    }

    @Test
    fun `buildModal returns a JDA modal with the right id`() {
        val built = modal.buildModal()
        assertEquals(InstallActivityChannelsModal.MODAL_NAME, built.id)
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
    fun `level-up pick writes LEVEL_UP_CHANNEL via batch upsert`() {
        val textChannel = mockk<TextChannel> { every { name } returns "level-ups" }
        stubLevelUpPick(11L)
        every { guild.getTextChannelById(11L) } returns textChannel
        val rowsSlot = slot<List<Pair<String, String>>>()
        every { configService.upsertAll("g1", capture(rowsSlot)) } returns emptyList()

        modal.handle(ctx, 0)

        verify(exactly = 1) { configService.upsertAll("g1", any()) }
        assertEquals(
            listOf(Configurations.LEVEL_UP_CHANNEL.configValue to "11"),
            rowsSlot.captured,
        )
    }

    @Test
    fun `achievement pick writes ACHIEVEMENT_ANNOUNCE_CHANNEL via batch upsert`() {
        val textChannel = mockk<TextChannel> { every { name } returns "achievements" }
        stubAchievementPick(22L)
        every { guild.getTextChannelById(22L) } returns textChannel
        val rowsSlot = slot<List<Pair<String, String>>>()
        every { configService.upsertAll("g1", capture(rowsSlot)) } returns emptyList()

        modal.handle(ctx, 0)

        verify(exactly = 1) { configService.upsertAll("g1", any()) }
        assertEquals(
            listOf(Configurations.ACHIEVEMENT_ANNOUNCE_CHANNEL.configValue to "22"),
            rowsSlot.captured,
        )
    }

    @Test
    fun `both picks write both keys in declaration order`() {
        val lu = mockk<TextChannel> { every { name } returns "level-ups" }
        val ach = mockk<TextChannel> { every { name } returns "achievements" }
        stubLevelUpPick(11L)
        stubAchievementPick(22L)
        every { guild.getTextChannelById(11L) } returns lu
        every { guild.getTextChannelById(22L) } returns ach
        val rowsSlot = slot<List<Pair<String, String>>>()
        every { configService.upsertAll("g1", capture(rowsSlot)) } returns emptyList()

        modal.handle(ctx, 0)

        verify(exactly = 1) { configService.upsertAll("g1", any()) }
        assertEquals(
            listOf(
                Configurations.LEVEL_UP_CHANNEL.configValue to "11",
                Configurations.ACHIEVEMENT_ANNOUNCE_CHANNEL.configValue to "22",
            ),
            rowsSlot.captured,
        )
    }

    @Test
    fun `picked level-up channel no longer exists rejects without writes`() {
        stubLevelUpPick(999L)
        every { guild.getTextChannelById(999L) } returns null

        modal.handle(ctx, 0)

        verify(exactly = 1) { event.reply(match<String> { it.contains("no longer exists") }) }
        verify(exactly = 0) { configService.upsertAll(any(), any()) }
    }

    @Test
    fun `picked achievement channel no longer exists rejects without writes`() {
        stubAchievementPick(999L)
        every { guild.getTextChannelById(999L) } returns null

        modal.handle(ctx, 0)

        verify(exactly = 1) { event.reply(match<String> { it.contains("no longer exists") }) }
        verify(exactly = 0) { configService.upsertAll(any(), any()) }
    }

    @Test
    fun `successful save replies with summary listing both channel names`() {
        val lu = mockk<TextChannel> { every { name } returns "level-ups" }
        val ach = mockk<TextChannel> { every { name } returns "achievements" }
        stubLevelUpPick(11L)
        stubAchievementPick(22L)
        every { guild.getTextChannelById(11L) } returns lu
        every { guild.getTextChannelById(22L) } returns ach

        modal.handle(ctx, 0)

        verify(exactly = 1) {
            event.reply(match<String> { it.contains("level-ups") && it.contains("achievements") })
        }
    }

    @Test
    fun `both pickers use min_values 1`() {
        val built = modal.buildModal()
        val menus = built.components
            .mapNotNull { it as? net.dv8tion.jda.api.components.label.Label }
            .map { it.child }
            .filterIsInstance<net.dv8tion.jda.api.components.selections.EntitySelectMenu>()
        assertEquals(2, menus.size, "expected two channel pickers")
        assertTrue(menus.all { it.minValues >= 1 }, "Discord rejects Label-wrapped menus with min_values=0")
    }
}
