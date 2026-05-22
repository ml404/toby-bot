package bot.toby.install.modal

import core.modal.ModalContext
import database.dto.ConfigDto.Configurations
import database.service.ConfigService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.interactions.modals.ModalMapping
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class InstallQuickChannelsModalTest {

    private lateinit var configService: ConfigService
    private lateinit var modal: InstallQuickChannelsModal
    private lateinit var event: ModalInteractionEvent
    private lateinit var ctx: ModalContext
    private lateinit var guild: Guild

    @BeforeEach
    fun setUp() {
        configService = mockk(relaxed = true)
        modal = InstallQuickChannelsModal(configService)
        event = mockk(relaxed = true)
        guild = mockk(relaxed = true) { every { id } returns "g1" }
        ctx = mockk {
            every { this@mockk.event } returns this@InstallQuickChannelsModalTest.event
            every { this@mockk.guild } returns this@InstallQuickChannelsModalTest.guild
        }
        every { event.reply(any<String>()) } returns mockk(relaxed = true) {
            every { setEphemeral(any()) } returns this
            every { queue() } just Runs
        }
        // Default: no selections.
        every { event.getValue(any<String>()) } returns null
    }

    private fun stubChannelPick(id: String, channelId: Long) {
        every { event.getValue(id) } returns mockk<ModalMapping> {
            every { asLongList } returns listOf(channelId)
        }
    }

    @Test
    fun `name and modal id constants align`() {
        assertEquals(InstallQuickChannelsModal.MODAL_NAME, modal.name)
        assertEquals("install_quick_channels", InstallQuickChannelsModal.MODAL_NAME)
    }

    @Test
    fun `buildModal returns a JDA modal with the right id and title`() {
        val built = modal.buildModal()
        assertEquals(InstallQuickChannelsModal.MODAL_NAME, built.id)
        assertNotNull(built.title)
    }

    @Test
    fun `no selections replies nothing changed without writes`() {
        modal.handle(ctx, 0)

        verify(exactly = 1) { event.reply(match<String> { it.contains("nothing changed", ignoreCase = true) }) }
        verify(exactly = 0) {
            configService.upsertConfig(any<String>(), any<String>(), any<String>())
        }
    }

    @Test
    fun `voice channel selection writes MOVE channel name`() {
        val voiceChannel = mockk<VoiceChannel> { every { name } returns "General Voice" }
        stubChannelPick(InstallQuickChannelsModal.FIELD_MOVE, 12345L)
        every { guild.getVoiceChannelById(12345L) } returns voiceChannel

        modal.handle(ctx, 0)

        verify(exactly = 1) {
            configService.upsertConfig(Configurations.MOVE.configValue, "General Voice", "g1")
        }
        verify(exactly = 0) {
            configService.upsertConfig(Configurations.LEADERBOARD_CHANNEL.configValue, any<String>(), "g1")
        }
    }

    @Test
    fun `text channel selection writes LEADERBOARD_CHANNEL id`() {
        val textChannel = mockk<TextChannel> { every { name } returns "leaderboard" }
        stubChannelPick(InstallQuickChannelsModal.FIELD_LEADERBOARD, 67890L)
        every { guild.getTextChannelById(67890L) } returns textChannel

        modal.handle(ctx, 0)

        verify(exactly = 1) {
            configService.upsertConfig(Configurations.LEADERBOARD_CHANNEL.configValue, "67890", "g1")
        }
        verify(exactly = 0) {
            configService.upsertConfig(Configurations.MOVE.configValue, any<String>(), "g1")
        }
    }

    @Test
    fun `both selections write both keys`() {
        val voiceChannel = mockk<VoiceChannel> { every { name } returns "Lobby" }
        val textChannel = mockk<TextChannel> { every { name } returns "scores" }
        stubChannelPick(InstallQuickChannelsModal.FIELD_MOVE, 1L)
        stubChannelPick(InstallQuickChannelsModal.FIELD_LEADERBOARD, 2L)
        every { guild.getVoiceChannelById(1L) } returns voiceChannel
        every { guild.getTextChannelById(2L) } returns textChannel

        modal.handle(ctx, 0)

        verify(exactly = 1) {
            configService.upsertConfig(Configurations.MOVE.configValue, "Lobby", "g1")
        }
        verify(exactly = 1) {
            configService.upsertConfig(Configurations.LEADERBOARD_CHANNEL.configValue, "2", "g1")
        }
    }

    @Test
    fun `picked voice channel no longer exists rejects with no writes`() {
        stubChannelPick(InstallQuickChannelsModal.FIELD_MOVE, 999L)
        every { guild.getVoiceChannelById(999L) } returns null

        modal.handle(ctx, 0)

        verify(exactly = 1) { event.reply(match<String> { it.contains("no longer exists") }) }
        verify(exactly = 0) {
            configService.upsertConfig(any<String>(), any<String>(), any<String>())
        }
    }

    @Test
    fun `picked text channel no longer exists rejects with no writes`() {
        stubChannelPick(InstallQuickChannelsModal.FIELD_LEADERBOARD, 888L)
        every { guild.getTextChannelById(888L) } returns null

        modal.handle(ctx, 0)

        verify(exactly = 1) { event.reply(match<String> { it.contains("no longer exists") }) }
        verify(exactly = 0) {
            configService.upsertConfig(any<String>(), any<String>(), any<String>())
        }
    }

    @Test
    fun `successful save replies with summary listing the channel names`() {
        val voice = mockk<VoiceChannel> { every { name } returns "Voice-A" }
        stubChannelPick(InstallQuickChannelsModal.FIELD_MOVE, 10L)
        every { guild.getVoiceChannelById(10L) } returns voice

        modal.handle(ctx, 0)

        verify(exactly = 1) {
            event.reply(match<String> { it.contains("Voice-A") })
        }
    }
}
