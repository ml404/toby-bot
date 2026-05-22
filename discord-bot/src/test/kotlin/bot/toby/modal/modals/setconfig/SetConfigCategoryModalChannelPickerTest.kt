package bot.toby.modal.modals.setconfig

import database.dto.ConfigDto.Configurations
import database.service.ConfigService
import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.components.selections.EntitySelectMenu
import net.dv8tion.jda.api.components.textinput.TextInput
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * Regression coverage for the channel-picker rendering on
 * [SetConfigCategoryModal.buildModal].
 *
 * The migration replaced typed-id [TextInput]s with native channel
 * [EntitySelectMenu] pickers for every `ChannelByIdStoreName` /
 * `ChannelByIdStoreId` spec across the 3 channel-bearing modals
 * (General, Jackpot, Lottery pools). These tests pin the new
 * rendering so an accidental drift to TextInput is caught at test
 * time.
 *
 * Pre-selection is validated by tracing the call into
 * `EntitySelectMenu.Builder.setDefaultValues(...)` via the captured
 * `defaultValues` on the built menu.
 */
class SetConfigCategoryModalChannelPickerTest {

    private lateinit var configService: ConfigService
    private lateinit var guild: Guild
    private val guildId = "100"

    @BeforeEach
    fun setUp() {
        configService = mockk(relaxed = true)
        guild = mockk(relaxed = true) {
            every { id } returns guildId
        }
    }

    // ---- 1 & 2: channel specs render as EntitySelectMenu, not TextInput ----

    @Test
    fun `MOVE renders as a voice EntitySelectMenu, not a TextInput`() {
        val modal = SetConfigGeneralModal(configService)
        val built = modal.buildModal(SetConfigGeneralModal.MODAL_NAME, guild) { null }

        val moveChild = findChild(built, SetConfigGeneralModal.FIELD_MOVE)
        assertNotNull(moveChild, "MOVE field must be present in the modal")
        assertTrue(
            moveChild is EntitySelectMenu,
            "MOVE renders as EntitySelectMenu (got ${moveChild!!::class.simpleName})",
        )
        val menu = moveChild as EntitySelectMenu
        assertTrue(
            menu.channelTypes.contains(ChannelType.VOICE),
            "MOVE picker filters to voice channels (got ${menu.channelTypes})",
        )
    }

    @Test
    fun `LEADERBOARD_CHANNEL renders as a text EntitySelectMenu`() {
        val modal = SetConfigGeneralModal(configService)
        val built = modal.buildModal(SetConfigGeneralModal.MODAL_NAME, guild) { null }

        val leaderboardChild = findChild(built, SetConfigGeneralModal.FIELD_LEADERBOARD_CHANNEL)
        assertNotNull(leaderboardChild)
        assertTrue(leaderboardChild is EntitySelectMenu)
        val menu = leaderboardChild as EntitySelectMenu
        assertTrue(menu.channelTypes.contains(ChannelType.TEXT))
    }

    // ---- 3 & 4: pre-population resolves stored value to a default-selected channel ----

    @Test
    fun `MOVE pre-selection resolves a stored channel name to a default value`() {
        val voiceChannel = mockk<VoiceChannel>(relaxed = true) {
            every { idLong } returns 42L
            every { id } returns "42"
        }
        every { guild.getVoiceChannelsByName("general", true) } returns listOf(voiceChannel)
        val modal = SetConfigGeneralModal(configService)

        val built = modal.buildModal(SetConfigGeneralModal.MODAL_NAME, guild) { key ->
            if (key == Configurations.MOVE) "general" else null
        }

        val menu = findChild(built, SetConfigGeneralModal.FIELD_MOVE) as EntitySelectMenu
        assertEquals(1, menu.defaultValues.size, "MOVE picker is pre-populated with the resolved channel")
        assertEquals(42L, menu.defaultValues.first().idLong)
    }

    @Test
    fun `LEADERBOARD pre-selection passes the stored id through unchanged`() {
        val textChannel = mockk<TextChannel>(relaxed = true) {
            every { idLong } returns 789L
        }
        every { guild.getTextChannelById(789L) } returns textChannel
        val modal = SetConfigGeneralModal(configService)

        val built = modal.buildModal(SetConfigGeneralModal.MODAL_NAME, guild) { key ->
            if (key == Configurations.LEADERBOARD_CHANNEL) "789" else null
        }

        val menu = findChild(built, SetConfigGeneralModal.FIELD_LEADERBOARD_CHANNEL) as EntitySelectMenu
        assertEquals(1, menu.defaultValues.size)
        assertEquals(789L, menu.defaultValues.first().idLong)
    }

    @Test
    fun `MOVE pre-selection is empty when stored name has no matching channel`() {
        every { guild.getVoiceChannelsByName(any(), any()) } returns emptyList()
        val modal = SetConfigGeneralModal(configService)

        val built = modal.buildModal(SetConfigGeneralModal.MODAL_NAME, guild) { key ->
            if (key == Configurations.MOVE) "ghost-channel" else null
        }

        val menu = findChild(built, SetConfigGeneralModal.FIELD_MOVE) as EntitySelectMenu
        assertTrue(menu.defaultValues.isEmpty())
    }

    @Test
    fun `LEADERBOARD pre-selection is empty when stored id is not a number`() {
        val modal = SetConfigGeneralModal(configService)

        val built = modal.buildModal(SetConfigGeneralModal.MODAL_NAME, guild) { key ->
            if (key == Configurations.LEADERBOARD_CHANNEL) "not-an-id" else null
        }

        val menu = findChild(built, SetConfigGeneralModal.FIELD_LEADERBOARD_CHANNEL) as EntitySelectMenu
        assertTrue(menu.defaultValues.isEmpty())
    }

    @Test
    fun `LEADERBOARD pre-selection is empty when stored id no longer exists in the guild`() {
        every { guild.getTextChannelById(any<Long>()) } returns null
        val modal = SetConfigGeneralModal(configService)

        val built = modal.buildModal(SetConfigGeneralModal.MODAL_NAME, guild) { key ->
            if (key == Configurations.LEADERBOARD_CHANNEL) "999" else null
        }

        val menu = findChild(built, SetConfigGeneralModal.FIELD_LEADERBOARD_CHANNEL) as EntitySelectMenu
        assertTrue(menu.defaultValues.isEmpty())
    }

    // ---- 6: mixing channel pickers with text fields keeps spec order ----

    @Test
    fun `general modal mixes text inputs and channel pickers in spec order`() {
        val modal = SetConfigGeneralModal(configService)
        val built = modal.buildModal(SetConfigGeneralModal.MODAL_NAME, guild) { null }
        val childIds = collectChildIds(built)
        // Spec order from SetConfigGeneralModal.fieldIds:
        // VOLUME, INTRO_VOLUME, DELETE_DELAY, MOVE, LEADERBOARD_CHANNEL.
        assertEquals(
            listOf(
                SetConfigGeneralModal.FIELD_VOLUME,
                SetConfigGeneralModal.FIELD_INTRO_VOLUME,
                SetConfigGeneralModal.FIELD_DELETE_DELAY,
                SetConfigGeneralModal.FIELD_MOVE,
                SetConfigGeneralModal.FIELD_LEADERBOARD_CHANNEL,
            ),
            childIds,
        )
    }

    // ---- 7: parametrised across all 4 channel-bearing config keys ----

    companion object {
        @JvmStatic
        fun channelBearingFields(): Stream<Arguments> = Stream.of(
            Arguments.of(
                "SetConfigGeneralModal.MOVE",
                Configurations.MOVE,
                SetConfigGeneralModal.MODAL_NAME,
                SetConfigGeneralModal.FIELD_MOVE,
                ChannelType.VOICE,
            ),
            Arguments.of(
                "SetConfigGeneralModal.LEADERBOARD_CHANNEL",
                Configurations.LEADERBOARD_CHANNEL,
                SetConfigGeneralModal.MODAL_NAME,
                SetConfigGeneralModal.FIELD_LEADERBOARD_CHANNEL,
                ChannelType.TEXT,
            ),
            Arguments.of(
                "SetConfigJackpotModal.CASINO_MODLOG_CHANNEL_ID",
                Configurations.CASINO_MODLOG_CHANNEL_ID,
                SetConfigJackpotModal.MODAL_NAME,
                SetConfigJackpotModal.FIELD_CASINO_MODLOG_CHANNEL_ID,
                ChannelType.TEXT,
            ),
            Arguments.of(
                "SetConfigLotteryPoolsModal.LOTTERY_CHANNEL",
                Configurations.LOTTERY_CHANNEL,
                SetConfigLotteryPoolsModal.MODAL_NAME,
                SetConfigLotteryPoolsModal.FIELD_CHANNEL,
                ChannelType.TEXT,
            ),
        )
    }

    @ParameterizedTest(name = "{0} renders a channel picker filtered to {4}")
    @MethodSource("channelBearingFields")
    fun `every channel-bearing config key renders as a channel picker`(
        label: String,
        key: Configurations,
        modalName: String,
        fieldId: String,
        channelType: ChannelType,
    ) {
        val modal: SetConfigCategoryModal = when (modalName) {
            SetConfigGeneralModal.MODAL_NAME -> SetConfigGeneralModal(configService)
            SetConfigJackpotModal.MODAL_NAME -> SetConfigJackpotModal(configService)
            SetConfigLotteryPoolsModal.MODAL_NAME -> SetConfigLotteryPoolsModal(configService)
            else -> error("Unhandled modal $modalName")
        }
        val built = modal.buildModal(modalName, guild) { null }
        val child = findChild(built, fieldId)
        assertNotNull(child, "$label: field $fieldId must be present")
        assertTrue(
            child is EntitySelectMenu,
            "$label: rendered as ${child!!::class.simpleName}, expected EntitySelectMenu",
        )
        val menu = child as EntitySelectMenu
        assertTrue(
            menu.channelTypes.contains(channelType),
            "$label: filtered to ${menu.channelTypes}, expected to contain $channelType",
        )
    }

    // ---- Helpers ----

    /**
     * Walk the modal's component tree to find the child component with
     * the given custom id. Modal components are wrapped in `Label`
     * nodes; we unwrap into either a TextInput or an EntitySelectMenu.
     */
    private fun findChild(
        modal: net.dv8tion.jda.api.modals.Modal,
        fieldId: String,
    ): net.dv8tion.jda.api.components.Component? {
        modal.components.forEach { top ->
            collectAll(top).firstOrNull {
                (it is TextInput && it.customId == fieldId) ||
                    (it is EntitySelectMenu && it.customId == fieldId)
            }?.let { return it }
        }
        return null
    }

    private fun collectChildIds(modal: net.dv8tion.jda.api.modals.Modal): List<String> =
        modal.components.flatMap { top ->
            collectAll(top).mapNotNull { c ->
                when (c) {
                    is TextInput -> c.customId
                    is EntitySelectMenu -> c.customId
                    else -> null
                }
            }
        }

    private fun collectAll(c: net.dv8tion.jda.api.components.Component): List<net.dv8tion.jda.api.components.Component> {
        val out = mutableListOf<net.dv8tion.jda.api.components.Component>()
        fun walk(node: net.dv8tion.jda.api.components.Component) {
            out += node
            if (node is net.dv8tion.jda.api.components.label.Label) {
                walk(node.child)
            }
        }
        walk(c)
        return out
    }

    @Suppress("unused")
    private fun assertChannelTypeFilter(menu: EntitySelectMenu, expected: ChannelType) {
        assertSame(expected, menu.channelTypes.firstOrNull())
    }

    @Suppress("unused")
    private fun assertNullablePreSelection(menu: EntitySelectMenu) {
        assertNull(menu.defaultValues.firstOrNull())
    }
}
