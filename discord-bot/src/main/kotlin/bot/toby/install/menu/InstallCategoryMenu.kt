package bot.toby.install.menu

import bot.toby.command.commands.moderation.SetConfigCommand
import bot.toby.install.ConfigReader
import bot.toby.install.InstallAuth
import bot.toby.install.InstallWizard
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
import common.logging.DiscordLogger
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

    private val log: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    /**
     * Dispatch table for top-level category picks. Each entry maps a
     * category token to one of:
     *
     * - [CategoryAction.OpenModal] — open the modal returned by the
     *   builder, then rearm to the section's detail menu.
     * - [CategoryAction.Transition] — perform an inline edit (e.g. swap
     *   the menu for a sub-menu) without opening a modal.
     */
    private val categoryActions: Map<String, CategoryAction> = mapOf(
        QUICK_CHANNELS_TOKEN to CategoryAction.OpenModal { quickChannels.buildModal() },
        SetConfigCommand.SUB_GENERAL to setconfigModal(general, SetConfigGeneralModal.MODAL_NAME),
        SetConfigCommand.SUB_ACTIVITY to setconfigModal(activity, SetConfigActivityModal.MODAL_NAME),
        SetConfigCommand.SUB_FEES to setconfigModal(fees, SetConfigFeesModal.MODAL_NAME),
        SetConfigCommand.SUB_JACKPOT to setconfigModal(jackpot, SetConfigJackpotModal.MODAL_NAME),
        SetConfigCommand.SUB_JACKPOT_ACTIVITY to setconfigModal(jackpotActivity, SetConfigJackpotActivityModal.MODAL_NAME),
        SetConfigCommand.SUB_POKER_STAKES to setconfigModal(pokerStakes, SetConfigPokerStakesModal.MODAL_NAME),
        SetConfigCommand.SUB_POKER_TABLE to setconfigModal(pokerTable, SetConfigPokerTableModal.MODAL_NAME),
        SetConfigCommand.SUB_BLACKJACK_RULES to setconfigModal(blackjackRules, SetConfigBlackjackRulesModal.MODAL_NAME),
        SetConfigCommand.SUB_BLACKJACK_TABLE to setconfigModal(blackjackTable, SetConfigBlackjackTableModal.MODAL_NAME),
        SetConfigCommand.SUB_LOTTERY_BASICS to setconfigModal(lotteryBasics, SetConfigLotteryBasicsModal.MODAL_NAME),
        SetConfigCommand.SUB_LOTTERY_POOLS to setconfigModal(lotteryPools, SetConfigLotteryPoolsModal.MODAL_NAME),
        SetConfigCommand.SUB_STAKES to CategoryAction.Transition(::showStakesGameMenu),
    )

    private fun setconfigModal(
        modal: bot.toby.modal.modals.setconfig.SetConfigCategoryModal,
        modalName: String,
    ): CategoryAction.OpenModal =
        CategoryAction.OpenModal { reader -> modal.buildModal(modalName, reader) }

    override fun handle(ctx: MenuContext, deleteDelay: Int) {
        val event = ctx.event
        if (!InstallAuth.requireOwner(event)) return
        val selected = event.selectedOptions.firstOrNull()?.value ?: run {
            event.reply("No option selected.").setEphemeral(true).queue()
            return
        }
        val reader = InstallWizard.configReader(configService, ctx.guild.id)
        val componentId = event.componentId
        log.info { "Install menu dispatch: componentId='$componentId' selected='$selected'" }
        when {
            componentId == InstallWizard.MENU_SECTION ->
                handleSectionPick(ctx, selected)
            componentId.startsWith("${InstallWizard.MENU_SECTION_DETAIL_PREFIX}:") ->
                handleCategoryPick(ctx, componentId, selected, reader)
            componentId == InstallWizard.MENU_CATEGORY_STAKES ->
                handleStakesPick(ctx, selected, reader)
            else -> {
                log.warn { "Install menu got unknown componentId '$componentId' — ignoring" }
                event.reply("Unknown menu `$componentId`.").setEphemeral(true).queue()
            }
        }
    }

    private fun handleSectionPick(ctx: MenuContext, sectionId: String) {
        val event = ctx.event
        val section = WizardSection.byId(sectionId) ?: run {
            event.reply("Unknown section `$sectionId`.").setEphemeral(true).queue()
            return
        }
        // Ack via deferEdit, then edit the source message via the bot
        // webhook. The bot-webhook edit goes through the same JDA
        // rate-limit bucket as DefaultMenuManager's prior
        // disable-all-rows edit (also a bot webhook), so the two are
        // serialized in submission order — our rearm always wins.
        //
        // The previous form (`event.editMessageEmbeds(...).setComponents(...).queue()`)
        // sent an interaction-response edit on a *different* token, which
        // raced against the manager's bot-webhook and could leave the
        // message stuck with the disabled previous-view components.
        event.deferEdit().queue {
            event.message.editMessageEmbeds(InstallWizard.sectionDetailEmbed(section))
                .setComponents(
                    ActionRow.of(InstallWizard.sectionDetailMenu(section)),
                    InstallWizard.backAndFinishRow(),
                )
                .queue()
        }
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
        // Same defer-then-bot-webhook pattern as `handleSectionPick` — see
        // the comment there for the race-condition this avoids.
        event.deferEdit().queue {
            event.message.editMessageEmbeds(InstallWizard.stakesGameMenuEmbed())
                .setComponents(
                    ActionRow.of(InstallWizard.stakesGameMenu(games)),
                    InstallWizard.backAndFinishRow(),
                )
                .queue()
        }
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
