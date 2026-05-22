package bot.toby.install.modal

import database.service.ConfigService
import io.mockk.mockk
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.selections.EntitySelectMenu
import net.dv8tion.jda.api.modals.Modal
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * Regression guard for every wizard channel-only modal.
 *
 * Discord rejects modal payloads where a `Label`-wrapped
 * [EntitySelectMenu] has `min_values=0`:
 *
 * ```
 * COMPONENT_REQUIRED_ZERO_MIN_VALUES:
 *   Components marked as required cannot have a min_values of 0
 * ```
 *
 * `Label` children are implicitly required, so every picker built
 * inside one of these modals must have `minValues >= 1`. This test
 * iterates every channel-only modal and asserts the constraint on
 * each menu — adding a fourth such modal automatically gets the
 * same protection by appending it to [pickerModals].
 */
internal class InstallChannelPickerModalsContractTest {

    companion object {
        @JvmStatic
        fun pickerModals(): Stream<Arguments> {
            val configService = mockk<ConfigService>(relaxed = true)
            return Stream.of(
                Arguments.of(
                    "InstallQuickChannelsModal",
                    InstallQuickChannelsModal(configService).buildModal(),
                ),
                Arguments.of(
                    "InstallJackpotChannelsModal",
                    InstallJackpotChannelsModal(configService).buildModal(),
                ),
                Arguments.of(
                    "InstallLotteryChannelsModal",
                    InstallLotteryChannelsModal(configService).buildModal(),
                ),
                Arguments.of(
                    "InstallActivityChannelsModal",
                    InstallActivityChannelsModal(configService).buildModal(),
                ),
            )
        }
    }

    @ParameterizedTest(name = "{0}: every EntitySelectMenu has minValues >= 1")
    @MethodSource("pickerModals")
    fun `every channel picker has min_values at least 1`(label: String, built: Modal) {
        val menus = built.collectSelectMenus()
        assertTrue(
            menus.isNotEmpty(),
            "$label produced no EntitySelectMenu — test setup is wrong, not a real failure",
        )
        menus.forEach { menu ->
            assertTrue(
                menu.minValues >= 1,
                "$label's picker '${menu.customId}' has minValues=${menu.minValues}; Discord " +
                    "rejects Label-wrapped select menus with min_values=0 " +
                    "(COMPONENT_REQUIRED_ZERO_MIN_VALUES). Required range must start at 1.",
            )
        }
    }

    private fun Modal.collectSelectMenus(): List<EntitySelectMenu> {
        val out = mutableListOf<EntitySelectMenu>()
        components.forEach { walk(it, out) }
        return out
    }

    private fun walk(node: Any, into: MutableList<EntitySelectMenu>) {
        when (node) {
            is EntitySelectMenu -> into += node
            is Label -> walk(node.child, into)
        }
    }
}
