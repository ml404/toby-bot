package bot.toby.install.menu

import bot.toby.command.commands.moderation.SetConfigCommand
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
import core.menu.Menu
import core.menu.MenuContext
import database.dto.ConfigDto.Configurations
import database.service.ConfigService
import net.dv8tion.jda.api.components.actionrow.ActionRow
import org.springframework.stereotype.Component

/**
 * The custom-install menu router. Three componentId tiers, all routed
 * to the same bean via `DefaultMenuManager.getMenu` (substring match on
 * the menu name `install_section`):
 *
 * - `install_section` — top-level. Selected value is a [WizardSection]
 *   id; we swap to the section's category list.
 * - `install_section_detail:<sectionId>` — categories within a section.
 *   Selected value is a `SetConfigCommand.SUB_*` token; we open that
 *   category's existing modal, or branch into the stakes-game sub-menu.
 * - `install_category_stakes` — game-picker for per-game stakes.
 *   Selected value is either `STAKE_ALL_TOKEN` (opens
 *   [InstallAllStakesModal]) or a [SetConfigStakesModal.Game] token
 *   (opens the existing per-game stakes modal).
 *
 * `DefaultMenuManager.handle` disables every action row on the source
 * message *before* this handler runs, so each branch re-arms the
 * components either inline (non-modal branches use
 * `event.editMessage*` as the interaction response) or in a
 * `replyModal.queue { … }` callback.
 */
@Component
class InstallCategoryMenu(
    private val configService: ConfigService,
    private val general: SetConfigGeneralModal,
    private val activity: SetConfigActivityModal,
    private val fees: SetConfigFeesModal,
    private val jackpot: SetConfigJackpotModal,
    private val jackpotActivity: SetConfigJackpotActivityModal,
    private val pokerStakes: SetConfigPokerStakesModal,
    private val pokerTable: SetConfigPokerTableModal,
    private val blackjackRules: SetConfigBlackjackRulesModal,
    private val blackjackTable: SetConfigBlackjackTableModal,
    private val lotteryBasics: SetConfigLotteryBasicsModal,
    private val lotteryPools: SetConfigLotteryPoolsModal,
    private val stakes: SetConfigStakesModal,
    private val allStakes: InstallAllStakesModal,
    private val quickChannels: InstallQuickChannelsModal,
) : Menu {

    override val name: String = InstallWizard.MENU_SECTION

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
        val guildId = ctx.guild.id
        val reader: (Configurations) -> String? =
            { key -> configService.getConfigByName(key.configValue, guildId)?.value }

        val componentId = event.componentId
        when {
            componentId == InstallWizard.MENU_SECTION ->
                handleSectionPick(ctx, selected, reader)
            componentId.startsWith("${InstallWizard.MENU_SECTION_DETAIL_PREFIX}:") ->
                handleCategoryPick(ctx, componentId, selected, reader)
            componentId == InstallWizard.MENU_CATEGORY_STAKES ->
                handleStakesPick(ctx, selected, reader)
            else -> event.reply("Unknown menu `$componentId`.").setEphemeral(true).queue()
        }
    }

    private fun handleSectionPick(
        ctx: MenuContext,
        sectionId: String,
        @Suppress("UNUSED_PARAMETER") reader: (Configurations) -> String?,
    ) {
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
        reader: (Configurations) -> String?,
    ) {
        val event = ctx.event
        val sectionId = componentId.substringAfter(":", missingDelimiterValue = "")
        val section = WizardSection.byId(sectionId) ?: run {
            event.reply("Unknown section `$sectionId`.").setEphemeral(true).queue()
            return
        }
        when (categoryToken) {
            QUICK_CHANNELS_TOKEN ->
                openModalAndRearm(ctx, section, quickChannels.buildModal())
            SetConfigCommand.SUB_GENERAL ->
                openModalAndRearm(ctx, section, general.buildModal(SetConfigGeneralModal.MODAL_NAME, reader))
            SetConfigCommand.SUB_ACTIVITY ->
                openModalAndRearm(ctx, section, activity.buildModal(SetConfigActivityModal.MODAL_NAME, reader))
            SetConfigCommand.SUB_FEES ->
                openModalAndRearm(ctx, section, fees.buildModal(SetConfigFeesModal.MODAL_NAME, reader))
            SetConfigCommand.SUB_JACKPOT ->
                openModalAndRearm(ctx, section, jackpot.buildModal(SetConfigJackpotModal.MODAL_NAME, reader))
            SetConfigCommand.SUB_JACKPOT_ACTIVITY ->
                openModalAndRearm(ctx, section, jackpotActivity.buildModal(SetConfigJackpotActivityModal.MODAL_NAME, reader))
            SetConfigCommand.SUB_POKER_STAKES ->
                openModalAndRearm(ctx, section, pokerStakes.buildModal(SetConfigPokerStakesModal.MODAL_NAME, reader))
            SetConfigCommand.SUB_POKER_TABLE ->
                openModalAndRearm(ctx, section, pokerTable.buildModal(SetConfigPokerTableModal.MODAL_NAME, reader))
            SetConfigCommand.SUB_BLACKJACK_RULES ->
                openModalAndRearm(ctx, section, blackjackRules.buildModal(SetConfigBlackjackRulesModal.MODAL_NAME, reader))
            SetConfigCommand.SUB_BLACKJACK_TABLE ->
                openModalAndRearm(ctx, section, blackjackTable.buildModal(SetConfigBlackjackTableModal.MODAL_NAME, reader))
            SetConfigCommand.SUB_LOTTERY_BASICS ->
                openModalAndRearm(ctx, section, lotteryBasics.buildModal(SetConfigLotteryBasicsModal.MODAL_NAME, reader))
            SetConfigCommand.SUB_LOTTERY_POOLS ->
                openModalAndRearm(ctx, section, lotteryPools.buildModal(SetConfigLotteryPoolsModal.MODAL_NAME, reader))
            SetConfigCommand.SUB_STAKES -> {
                val games = SetConfigStakesModal.Game.entries.map { it.label to it.token }
                event.editMessageEmbeds(InstallWizard.stakesGameMenuEmbed())
                    .setComponents(
                        ActionRow.of(InstallWizard.stakesGameMenu(games)),
                        InstallWizard.backAndFinishRow(),
                    )
                    .queue()
            }
            else -> event.reply("Unknown category `$categoryToken`.").setEphemeral(true).queue()
        }
    }

    private fun handleStakesPick(
        ctx: MenuContext,
        selectedToken: String,
        reader: (Configurations) -> String?,
    ) {
        val event = ctx.event
        if (selectedToken == InstallWizard.STAKE_ALL_TOKEN) {
            val (currentMin, currentMax) = allStakes.readCurrentDefaults(reader)
            // Pass the Economy section so the modal-close rearm lands back
            // in the section-detail menu (the natural home for Per-game stakes).
            val section = WizardSection.ECONOMY
            openModalAndRearm(ctx, section, allStakes.buildModal(currentMin, currentMax))
            return
        }
        val game = SetConfigStakesModal.Game.byToken(selectedToken) ?: run {
            event.reply("Unknown game `$selectedToken`.").setEphemeral(true).queue()
            return
        }
        openModalAndRearm(
            ctx,
            WizardSection.ECONOMY,
            stakes.buildModal(SetConfigStakesModal.customIdFor(game), reader),
        )
    }

    /**
     * Open [modal] and, once it's open, rearm the message back to the
     * [section]'s detail menu so the owner can pick another category in
     * the same section without re-navigating.
     */
    private fun openModalAndRearm(
        ctx: MenuContext,
        section: WizardSection,
        modal: net.dv8tion.jda.api.modals.Modal,
    ) {
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
}
