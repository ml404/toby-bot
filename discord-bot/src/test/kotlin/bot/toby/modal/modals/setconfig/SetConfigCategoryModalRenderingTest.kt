package bot.toby.modal.modals.setconfig

import database.dto.ConfigDto.Configurations
import database.service.guild.ConfigService
import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.components.selections.EntitySelectMenu
import net.dv8tion.jda.api.components.textinput.TextInput
import net.dv8tion.jda.api.entities.Guild
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * Regression coverage for [SetConfigCategoryModal.buildModal]'s
 * uniform `TextInput`-only rendering.
 *
 * **Why this test exists**: an earlier attempt rendered channel
 * fields ([SetConfigFieldValidator.FieldSpec.ChannelByIdStoreName] /
 * `ChannelByIdStoreId`) as native `EntitySelectMenu` channel pickers
 * inside the same modal as the other TextInput fields. Discord's
 * modal API does not accept mixed component types — the entire
 * modal failed to open with client-side "Interaction failed",
 * across General, Jackpot, and Lottery pools. These tests pin the
 * single-component-type contract so we don't regress.
 *
 * Channel-picker UX is provided via dedicated channel-only modals
 * (e.g. `InstallQuickChannelsModal`) reached from the install
 * wizard's section-detail menus, never embedded in a multi-field
 * setconfig modal.
 */
class SetConfigCategoryModalRenderingTest {

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

    @Test
    fun `General modal renders 5 TextInputs and zero EntitySelectMenus`() {
        val modal = SetConfigGeneralModal(configService)
        val built = modal.buildModal(SetConfigGeneralModal.MODAL_NAME, guild) { null }

        val textInputs = built.allComponents().filterIsInstance<TextInput>()
        val selectMenus = built.allComponents().filterIsInstance<EntitySelectMenu>()
        assertEquals(5, textInputs.size, "General modal has 5 TextInputs")
        assertTrue(
            selectMenus.isEmpty(),
            "General modal must not embed EntitySelectMenu — Discord rejects mixed modals (got ${selectMenus.size})",
        )
    }

    @Test
    fun `Jackpot modal contains no EntitySelectMenu`() {
        val modal = SetConfigJackpotModal(configService)
        val built = modal.buildModal(SetConfigJackpotModal.MODAL_NAME, guild) { null }
        assertTrue(
            built.allComponents().filterIsInstance<EntitySelectMenu>().isEmpty(),
            "Jackpot modal must not embed EntitySelectMenu (Discord rejects mixed modals)",
        )
    }

    @Test
    fun `Lottery pools modal contains no EntitySelectMenu`() {
        val modal = SetConfigLotteryPoolsModal(configService)
        val built = modal.buildModal(SetConfigLotteryPoolsModal.MODAL_NAME, guild) { null }
        assertTrue(
            built.allComponents().filterIsInstance<EntitySelectMenu>().isEmpty(),
            "Lottery pools modal must not embed EntitySelectMenu (Discord rejects mixed modals)",
        )
    }

    companion object {
        @JvmStatic
        fun channelBearingFields(): Stream<Arguments> = Stream.of(
            Arguments.of(
                "SetConfigGeneralModal.MOVE",
                Configurations.MOVE,
                SetConfigGeneralModal.MODAL_NAME,
                SetConfigGeneralModal.FIELD_MOVE,
            ),
            Arguments.of(
                "SetConfigGeneralModal.LEADERBOARD_CHANNEL",
                Configurations.LEADERBOARD_CHANNEL,
                SetConfigGeneralModal.MODAL_NAME,
                SetConfigGeneralModal.FIELD_LEADERBOARD_CHANNEL,
            ),
            Arguments.of(
                "SetConfigJackpotModal.CASINO_MODLOG_CHANNEL_ID",
                Configurations.CASINO_MODLOG_CHANNEL_ID,
                SetConfigJackpotModal.MODAL_NAME,
                SetConfigJackpotModal.FIELD_CASINO_MODLOG_CHANNEL_ID,
            ),
            Arguments.of(
                "SetConfigLotteryPoolsModal.LOTTERY_CHANNEL",
                Configurations.LOTTERY_CHANNEL,
                SetConfigLotteryPoolsModal.MODAL_NAME,
                SetConfigLotteryPoolsModal.FIELD_CHANNEL,
            ),
        )
    }

    @ParameterizedTest(name = "{0} renders as TextInput, never EntitySelectMenu")
    @MethodSource("channelBearingFields")
    fun `every channel-bearing config key renders as a TextInput`(
        label: String,
        @Suppress("UNUSED_PARAMETER") key: Configurations,
        modalName: String,
        fieldId: String,
    ) {
        val modal: SetConfigCategoryModal = when (modalName) {
            SetConfigGeneralModal.MODAL_NAME -> SetConfigGeneralModal(configService)
            SetConfigJackpotModal.MODAL_NAME -> SetConfigJackpotModal(configService)
            SetConfigLotteryPoolsModal.MODAL_NAME -> SetConfigLotteryPoolsModal(configService)
            else -> error("Unhandled modal $modalName")
        }
        val built = modal.buildModal(modalName, guild) { null }
        val matching = built.allComponents().filter {
            (it is TextInput && it.customId == fieldId) ||
                (it is EntitySelectMenu && it.customId == fieldId)
        }
        assertEquals(1, matching.size, "$label: field $fieldId should appear exactly once")
        val component = matching.single()
        assertTrue(
            component is TextInput,
            "$label: must render as TextInput, got ${component::class.simpleName} — " +
                "mixing TextInput + EntitySelectMenu in one modal triggers " +
                "client-side 'Interaction failed'",
        )
    }

    @Test
    fun `every SetConfig modal is single-typed TextInput-only`() {
        // Defence-in-depth: any modal containing a SelectMenu child
        // breaks Discord's mixed-modal rule. Walk every concrete
        // modal class registered as a Spring bean and assert none
        // embed an EntitySelectMenu.
        val concretes = listOf<SetConfigCategoryModal>(
            SetConfigGeneralModal(configService),
            SetConfigActivityModal(configService, mockk(relaxed = true)),
            SetConfigFeesModal(configService),
            SetConfigJackpotModal(configService),
            SetConfigJackpotActivityModal(configService),
            SetConfigPokerStakesModal(configService),
            SetConfigPokerTableModal(configService),
            SetConfigBlackjackRulesModal(configService),
            SetConfigBlackjackTableModal(configService),
            SetConfigLotteryBasicsModal(configService),
            SetConfigLotteryPoolsModal(configService),
        )
        concretes.forEach { modal ->
            val modalName = (modal::class.java.getDeclaredField("name").apply { isAccessible = true }
                .get(modal) as String)
            val built = modal.buildModal(modalName, guild) { null }
            val selectMenus = built.allComponents().filterIsInstance<EntitySelectMenu>()
            assertTrue(
                selectMenus.isEmpty(),
                "${modal::class.simpleName} embeds an EntitySelectMenu — Discord rejects mixed modals",
            )
        }
    }

    // ---- Helpers ----

    private fun net.dv8tion.jda.api.modals.Modal.allComponents(): List<net.dv8tion.jda.api.components.Component> {
        val out = mutableListOf<net.dv8tion.jda.api.components.Component>()
        components.forEach { top -> walk(top, out) }
        return out
    }

    private fun walk(node: net.dv8tion.jda.api.components.Component, into: MutableList<net.dv8tion.jda.api.components.Component>) {
        into += node
        if (node is net.dv8tion.jda.api.components.label.Label) {
            walk(node.child, into)
        }
    }
}
