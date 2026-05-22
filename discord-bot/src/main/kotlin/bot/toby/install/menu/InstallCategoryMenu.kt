package bot.toby.install.menu

import bot.toby.command.commands.moderation.SetConfigCommand
import bot.toby.install.InstallWizard
import bot.toby.install.InstallWizard.ConfigReader
import bot.toby.install.QUICK_CHANNELS_TOKEN
import bot.toby.install.WizardSection
import bot.toby.install.modal.InstallAllStakesModal
import bot.toby.install.modal.InstallQuickChannelsModal
import bot.toby.modal.modals.setconfig.SetConfigActivityModal
import bot.toby.modal.modals.setconfig.SetConfigBlackjackRulesModal
import bot.toby.modal.modals.setconfig.SetConfigBlackjackTableModal
import bot.toby.modal.modals.setconfig.SetConfigFeesModal
import bot.toby.modal.modals.setconfig.SetConfigGeneralModal
import bot.toby.modal.modals.setconfig.SetConfigJackpotActivityModal
import bot.toby.modal.modals.setconfig.SetConfigJackpotModal
import bot.toby.modal.modals.setconfig.SetConfigLotteryBasicsModal
import bot.toby.modal.modals.setconfig.SetConfigLotteryPoolsModal
import bot.toby.modal.modals.setconfig.SetConfigPokerStakesModal
import bot.toby.modal.modals.setconfig.SetConfigPokerTableModal
import bot.toby.modal.modals.setconfig.SetConfigStakesModal
import core.menu.Menu
import core.menu.MenuContext
import database.service.ConfigService
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.modals.Modal
import org.springframework.stereotype.Component

/**
 * The custom-install menu router. Three componentId tiers, all routed
 * to the same bean via `DefaultMenuManager.getMenu` (substring match on
 * the menu name `install_section`):
 *
 * - `install_section` — top-level. Selected value is a [WizardSection]
 *   id; we swap to the section's category list.
 * - `install_section_detail:<sectionId>` — categories within a section.
 *   Selected value is a category token; we dispatch via [categoryActions]
 *   to open a modal, transition to a sub-menu, etc.
 * - `install_category_stakes` — game-picker for per-game stakes.
 *
 * Adding a new category is a one-entry change in [categoryActions] —
 * the menu's `handle` doesn't grow a new branch.
 */
@Component
class InstallCategoryMenu(
    private val configService: ConfigService,
    general: SetConfigGeneralModal,
    activity: SetConfigActivityModal,
    fees: SetConfigFeesModal,
    jackpot: SetConfigJackpotModal,
    jackpotActivity: SetConfigJackpotActivityModal,
    pokerStakes: SetConfigPokerStakesModal,
    pokerTable: SetConfigPokerTableModal,
    blackjackRules: SetConfigBlackjackRulesModal,
    blackjackTable: SetConfigBlackjackTableModal,
    lotteryBasics: SetConfigLotteryBasicsModal,
    lotteryPools: SetConfigLotteryPoolsModal,
    private val stakes: SetConfigStakesModal,
    private val allStakes: InstallAllStakesModal,
    quickChannels: InstallQuickChannelsModal,
) : Menu {

    override val name: String = InstallWizard.MENU_SECTION

    /**
     * Dispatch table for top-level category picks. Each entry maps a
     * category token to one of:
     *
     * - [CategoryAction.OpenModal] — open the modal returned by the
     *   builder, then rearm to the section's detail menu.
     * - [CategoryAction.Transition] — perform an inline edit (e.g. swap
     *   the menu for a sub-menu) without opening a modal.
     */
    private val categoryActions: Map<String, CategoryAction> = buildMap {
        fun openModal(token: String, build: (ConfigReader) -> Modal) {
            put(token, CategoryAction.OpenModal(build))
        }
        openModal(QUICK_CHANNELS_TOKEN) { quickChannels.buildModal() }
        openModal(SetConfigCommand.SUB_GENERAL) { r -> general.buildModal(SetConfigGeneralModal.MODAL_NAME, r) }
        openModal(SetConfigCommand.SUB_ACTIVITY) { r -> activity.buildModal(SetConfigActivityModal.MODAL_NAME, r) }
        openModal(SetConfigCommand.SUB_FEES) { r -> fees.buildModal(SetConfigFeesModal.MODAL_NAME, r) }
        openModal(SetConfigCommand.SUB_JACKPOT) { r -> jackpot.buildModal(SetConfigJackpotModal.MODAL_NAME, r) }
        openModal(SetConfigCommand.SUB_JACKPOT_ACTIVITY) { r -> jackpotActivity.buildModal(SetConfigJackpotActivityModal.MODAL_NAME, r) }
        openModal(SetConfigCommand.SUB_POKER_STAKES) { r -> pokerStakes.buildModal(SetConfigPokerStakesModal.MODAL_NAME, r) }
        openModal(SetConfigCommand.SUB_POKER_TABLE) { r -> pokerTable.buildModal(SetConfigPokerTableModal.MODAL_NAME, r) }
        openModal(SetConfigCommand.SUB_BLACKJACK_RULES) { r -> blackjackRules.buildModal(SetConfigBlackjackRulesModal.MODAL_NAME, r) }
        openModal(SetConfigCommand.SUB_BLACKJACK_TABLE) { r -> blackjackTable.buildModal(SetConfigBlackjackTableModal.MODAL_NAME, r) }
        openModal(SetConfigCommand.SUB_LOTTERY_BASICS) { r -> lotteryBasics.buildModal(SetConfigLotteryBasicsModal.MODAL_NAME, r) }
        openModal(SetConfigCommand.SUB_LOTTERY_POOLS) { r -> lotteryPools.buildModal(SetConfigLotteryPoolsModal.MODAL_NAME, r) }
        put(SetConfigCommand.SUB_STAKES, CategoryAction.Transition(::showStakesGameMenu))
    }

    override fun handle(ctx: MenuContext, deleteDelay: Int) {
        val event = ctx.event
        if (ctx.member?.isOwner != true) {
            event.reply("Only the server owner can use the install wizard.")
                .setEphemeral(true).queue()
            return
        }
        val selected = event.selectedOptions.firstOrNull()?.value ?: run {
            event.reply("No option selected.").setEphemeral(true).queue()
            return
        }
        val reader = InstallWizard.configReader(configService, ctx.guild.id)
        val componentId = event.componentId
        when {
            componentId == InstallWizard.MENU_SECTION ->
                handleSectionPick(ctx, selected)
            componentId.startsWith("${InstallWizard.MENU_SECTION_DETAIL_PREFIX}:") ->
                handleCategoryPick(ctx, componentId, selected, reader)
            componentId == InstallWizard.MENU_CATEGORY_STAKES ->
                handleStakesPick(ctx, selected, reader)
            else -> event.reply("Unknown menu `$componentId`.").setEphemeral(true).queue()
        }
    }

    private fun handleSectionPick(ctx: MenuContext, sectionId: String) {
        val event = ctx.event
        val section = WizardSection.byId(sectionId) ?: run {
            event.reply("Unknown section `$sectionId`.").setEphemeral(true).queue()
            return
        }
        event.editMessageEmbeds(InstallWizard.sectionDetailEmbed(section))
            .setComponents(
                ActionRow.of(InstallWizard.sectionDetailMenu(section)),
                InstallWizard.backAndFinishRow(),
            )
            .queue()
    }

    private fun handleCategoryPick(
        ctx: MenuContext,
        componentId: String,
        categoryToken: String,
        reader: ConfigReader,
    ) {
        val event = ctx.event
        val sectionId = componentId.substringAfter(":", missingDelimiterValue = "")
        val section = WizardSection.byId(sectionId) ?: run {
            event.reply("Unknown section `$sectionId`.").setEphemeral(true).queue()
            return
        }
        val action = categoryActions[categoryToken] ?: run {
            event.reply("Unknown category `$categoryToken`.").setEphemeral(true).queue()
            return
        }
        when (action) {
            is CategoryAction.OpenModal -> openModalAndRearm(ctx, section, action.build(reader))
            is CategoryAction.Transition -> action.run(ctx)
        }
    }

    private fun handleStakesPick(ctx: MenuContext, selectedToken: String, reader: ConfigReader) {
        val event = ctx.event
        val section = WizardSection.ECONOMY
        if (selectedToken == InstallWizard.STAKE_ALL_TOKEN) {
            val currentMin = reader(SetConfigStakesModal.Game.DICE.minKey)
            val currentMax = reader(SetConfigStakesModal.Game.DICE.maxKey)
            openModalAndRearm(ctx, section, allStakes.buildModal(currentMin, currentMax))
            return
        }
        val game = SetConfigStakesModal.Game.byToken(selectedToken) ?: run {
            event.reply("Unknown game `$selectedToken`.").setEphemeral(true).queue()
            return
        }
        openModalAndRearm(ctx, section, stakes.buildModal(SetConfigStakesModal.customIdFor(game), reader))
    }

    private fun showStakesGameMenu(ctx: MenuContext) {
        val event = ctx.event
        val games = SetConfigStakesModal.Game.entries.map { it.label to it.token }
        event.editMessageEmbeds(InstallWizard.stakesGameMenuEmbed())
            .setComponents(
                ActionRow.of(InstallWizard.stakesGameMenu(games)),
                InstallWizard.backAndFinishRow(),
            )
            .queue()
    }

    /**
     * Open [modal] and, once it's open, rearm the message back to the
     * [section]'s detail menu so the owner can pick another category in
     * the same section without re-navigating.
     */
    private fun openModalAndRearm(ctx: MenuContext, section: WizardSection, modal: Modal) {
        val event = ctx.event
        event.replyModal(modal).queue {
            event.message.editMessageEmbeds(InstallWizard.sectionDetailEmbed(section))
                .setComponents(
                    ActionRow.of(InstallWizard.sectionDetailMenu(section)),
                    InstallWizard.backAndFinishRow(),
                )
                .queue()
        }
    }

    private sealed interface CategoryAction {
        class OpenModal(val build: (ConfigReader) -> Modal) : CategoryAction
        class Transition(val run: (MenuContext) -> Unit) : CategoryAction
    }
}
