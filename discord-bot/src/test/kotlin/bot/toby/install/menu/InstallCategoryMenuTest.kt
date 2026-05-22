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
import core.menu.MenuContext
import database.dto.ConfigDto
import database.dto.ConfigDto.Configurations
import database.service.ConfigService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.components.selections.SelectOption
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.modals.Modal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

internal class InstallCategoryMenuTest {

    private lateinit var configService: ConfigService
    private lateinit var general: SetConfigGeneralModal
    private lateinit var activity: SetConfigActivityModal
    private lateinit var fees: SetConfigFeesModal
    private lateinit var jackpot: SetConfigJackpotModal
    private lateinit var jackpotActivity: SetConfigJackpotActivityModal
    private lateinit var pokerStakes: SetConfigPokerStakesModal
    private lateinit var pokerTable: SetConfigPokerTableModal
    private lateinit var blackjackRules: SetConfigBlackjackRulesModal
    private lateinit var blackjackTable: SetConfigBlackjackTableModal
    private lateinit var lotteryBasics: SetConfigLotteryBasicsModal
    private lateinit var lotteryPools: SetConfigLotteryPoolsModal
    private lateinit var stakes: SetConfigStakesModal
    private lateinit var allStakes: InstallAllStakesModal
    private lateinit var quickChannels: InstallQuickChannelsModal
    private lateinit var menu: InstallCategoryMenu

    private lateinit var event: StringSelectInteractionEvent
    private lateinit var ctx: MenuContext
    private lateinit var guild: Guild
    private lateinit var member: Member

    @BeforeEach
    fun setUp() {
        configService = mockk(relaxed = true)
        general = mockk(relaxed = true)
        activity = mockk(relaxed = true)
        fees = mockk(relaxed = true)
        jackpot = mockk(relaxed = true)
        jackpotActivity = mockk(relaxed = true)
        pokerStakes = mockk(relaxed = true)
        pokerTable = mockk(relaxed = true)
        blackjackRules = mockk(relaxed = true)
        blackjackTable = mockk(relaxed = true)
        lotteryBasics = mockk(relaxed = true)
        lotteryPools = mockk(relaxed = true)
        stakes = mockk(relaxed = true)
        allStakes = mockk(relaxed = true)
        quickChannels = mockk(relaxed = true)
        menu = InstallCategoryMenu(
            configService,
            general, activity, fees, jackpot, jackpotActivity,
            pokerStakes, pokerTable, blackjackRules, blackjackTable,
            lotteryBasics, lotteryPools, stakes, allStakes, quickChannels,
        )

        event = mockk(relaxed = true)
        guild = mockk(relaxed = true) { every { id } returns "g1" }
        member = mockk(relaxed = true) { every { isOwner } returns true }
        ctx = mockk {
            every { this@mockk.event } returns this@InstallCategoryMenuTest.event
            every { this@mockk.guild } returns this@InstallCategoryMenuTest.guild
            every { this@mockk.member } returns this@InstallCategoryMenuTest.member
        }
        every { event.reply(any<String>()) } returns mockk(relaxed = true) {
            every { setEphemeral(any()) } returns this
            every { queue() } just Runs
        }
        every { event.replyModal(any<Modal>()) } returns mockk(relaxed = true) {
            every { queue() } just Runs
            every { queue(any()) } just Runs
        }
        every { event.editMessageEmbeds(any<net.dv8tion.jda.api.entities.MessageEmbed>()) } returns
            mockk(relaxed = true) {
                every { setComponents(*anyVararg()) } returns this
                every { queue() } just Runs
            }
    }

    @Test
    fun `name is install_section`() {
        assertEquals(InstallWizard.MENU_SECTION, menu.name)
    }

    @Test
    fun `non-owner is rejected ephemerally`() {
        every { member.isOwner } returns false
        every { event.componentId } returns InstallWizard.MENU_SECTION
        every { event.selectedOptions } returns listOf(SelectOption.of("x", WizardSection.GENERAL.id))

        menu.handle(ctx, 0)

        verify(exactly = 1) { event.reply(any<String>()) }
        verify(exactly = 0) { event.replyModal(any<Modal>()) }
        verify(exactly = 0) { event.editMessageEmbeds(any<net.dv8tion.jda.api.entities.MessageEmbed>()) }
    }

    @Test
    fun `no selected option replies ephemerally`() {
        every { event.componentId } returns InstallWizard.MENU_SECTION
        every { event.selectedOptions } returns emptyList()

        menu.handle(ctx, 0)

        verify(exactly = 1) { event.reply(any<String>()) }
    }

    @Test
    fun `unknown menu componentId replies ephemerally`() {
        every { event.componentId } returns "not_a_menu"
        every { event.selectedOptions } returns listOf(SelectOption.of("x", "y"))

        menu.handle(ctx, 0)

        verify(exactly = 1) { event.reply(any<String>()) }
    }

    @Test
    fun `top-level section pick edits to section detail menu`() {
        every { event.componentId } returns InstallWizard.MENU_SECTION
        every { event.selectedOptions } returns listOf(SelectOption.of("x", WizardSection.POKER.id))

        menu.handle(ctx, 0)

        verify(exactly = 1) { event.editMessageEmbeds(any<net.dv8tion.jda.api.entities.MessageEmbed>()) }
        verify(exactly = 0) { event.replyModal(any<Modal>()) }
    }

    @Test
    fun `unknown section id replies ephemerally`() {
        every { event.componentId } returns InstallWizard.MENU_SECTION
        every { event.selectedOptions } returns listOf(SelectOption.of("x", "nope"))

        menu.handle(ctx, 0)

        verify(exactly = 1) { event.reply(any<String>()) }
    }

    // ---- category picks (parametrised) ----

    companion object {
        @JvmStatic
        fun simpleCategoryCases(): Stream<Arguments> = Stream.of(
            Arguments.of(SetConfigCommand.SUB_GENERAL, SetConfigGeneralModal.MODAL_NAME),
            Arguments.of(SetConfigCommand.SUB_ACTIVITY, SetConfigActivityModal.MODAL_NAME),
            Arguments.of(SetConfigCommand.SUB_FEES, SetConfigFeesModal.MODAL_NAME),
            Arguments.of(SetConfigCommand.SUB_JACKPOT, SetConfigJackpotModal.MODAL_NAME),
            Arguments.of(SetConfigCommand.SUB_JACKPOT_ACTIVITY, SetConfigJackpotActivityModal.MODAL_NAME),
            Arguments.of(SetConfigCommand.SUB_POKER_STAKES, SetConfigPokerStakesModal.MODAL_NAME),
            Arguments.of(SetConfigCommand.SUB_POKER_TABLE, SetConfigPokerTableModal.MODAL_NAME),
            Arguments.of(SetConfigCommand.SUB_BLACKJACK_RULES, SetConfigBlackjackRulesModal.MODAL_NAME),
            Arguments.of(SetConfigCommand.SUB_BLACKJACK_TABLE, SetConfigBlackjackTableModal.MODAL_NAME),
            Arguments.of(SetConfigCommand.SUB_LOTTERY_BASICS, SetConfigLotteryBasicsModal.MODAL_NAME),
            Arguments.of(SetConfigCommand.SUB_LOTTERY_POOLS, SetConfigLotteryPoolsModal.MODAL_NAME),
        )
    }

    @ParameterizedTest(name = "category {0} opens modal {1}")
    @MethodSource("simpleCategoryCases")
    fun `each simple category opens its matching modal`(
        categoryToken: String,
        expectedModalName: String,
    ) {
        every { event.componentId } returns InstallWizard.sectionDetailMenuId("any")
        every { event.selectedOptions } returns listOf(SelectOption.of("x", categoryToken))

        // Stub the modal bean (whichever) to return a known modal id.
        val built = mockk<Modal>(relaxed = true) { every { id } returns expectedModalName }
        every { general.buildModal(SetConfigGeneralModal.MODAL_NAME, any()) } returns built
        every { activity.buildModal(SetConfigActivityModal.MODAL_NAME, any()) } returns built
        every { fees.buildModal(SetConfigFeesModal.MODAL_NAME, any()) } returns built
        every { jackpot.buildModal(SetConfigJackpotModal.MODAL_NAME, any()) } returns built
        every { jackpotActivity.buildModal(SetConfigJackpotActivityModal.MODAL_NAME, any()) } returns built
        every { pokerStakes.buildModal(SetConfigPokerStakesModal.MODAL_NAME, any()) } returns built
        every { pokerTable.buildModal(SetConfigPokerTableModal.MODAL_NAME, any()) } returns built
        every { blackjackRules.buildModal(SetConfigBlackjackRulesModal.MODAL_NAME, any()) } returns built
        every { blackjackTable.buildModal(SetConfigBlackjackTableModal.MODAL_NAME, any()) } returns built
        every { lotteryBasics.buildModal(SetConfigLotteryBasicsModal.MODAL_NAME, any()) } returns built
        every { lotteryPools.buildModal(SetConfigLotteryPoolsModal.MODAL_NAME, any()) } returns built

        // Make the componentId start with a valid section detail prefix.
        every { event.componentId } returns InstallWizard.sectionDetailMenuId(WizardSection.GENERAL.id)

        menu.handle(ctx, 0)

        val modalSlot = slot<Modal>()
        verify(exactly = 1) { event.replyModal(capture(modalSlot)) }
        assertEquals(expectedModalName, modalSlot.captured.id)
    }

    @Test
    fun `reader passed to buildModal delegates to configService getConfigByName`() {
        val readerSlot = slot<(Configurations) -> String?>()
        val built = mockk<Modal>(relaxed = true) { every { id } returns SetConfigGeneralModal.MODAL_NAME }
        every { general.buildModal(SetConfigGeneralModal.MODAL_NAME, capture(readerSlot)) } returns built
        every { event.componentId } returns InstallWizard.sectionDetailMenuId(WizardSection.GENERAL.id)
        every { event.selectedOptions } returns listOf(SelectOption.of("x", SetConfigCommand.SUB_GENERAL))
        every {
            configService.getConfigByName(any<String>(), any<String>())
        } returns ConfigDto(Configurations.VOLUME.configValue, "75", "g1")

        menu.handle(ctx, 0)

        assertNotNull(readerSlot.captured)
        assertEquals("75", readerSlot.captured.invoke(Configurations.VOLUME))
        verify { configService.getConfigByName(Configurations.VOLUME.configValue, "g1") }
    }

    @Test
    fun `stakes category swaps to game sub-menu without opening a modal`() {
        every { event.componentId } returns InstallWizard.sectionDetailMenuId(WizardSection.ECONOMY.id)
        every { event.selectedOptions } returns listOf(SelectOption.of("x", SetConfigCommand.SUB_STAKES))

        menu.handle(ctx, 0)

        verify(exactly = 1) { event.editMessageEmbeds(any<net.dv8tion.jda.api.entities.MessageEmbed>()) }
        verify(exactly = 0) { event.replyModal(any<Modal>()) }
    }

    @ParameterizedTest
    @EnumSource(SetConfigStakesModal.Game::class)
    fun `game token in stakes sub-menu opens that game's stakes modal`(game: SetConfigStakesModal.Game) {
        val expectedId = SetConfigStakesModal.customIdFor(game)
        val built = mockk<Modal>(relaxed = true) { every { id } returns expectedId }
        every { stakes.buildModal(expectedId, any()) } returns built
        every { event.componentId } returns InstallWizard.MENU_CATEGORY_STAKES
        every { event.selectedOptions } returns listOf(SelectOption.of("x", game.token))

        menu.handle(ctx, 0)

        val modalSlot = slot<Modal>()
        verify(exactly = 1) { event.replyModal(capture(modalSlot)) }
        assertEquals(expectedId, modalSlot.captured.id)
    }

    @Test
    fun `apply-to-all token in stakes sub-menu opens the all-stakes modal`() {
        val expectedId = InstallAllStakesModal.MODAL_NAME
        val built = mockk<Modal>(relaxed = true) { every { id } returns expectedId }
        every { allStakes.buildModal(any(), any()) } returns built
        every { allStakes.readCurrentDefaults(any()) } returns ("100" to "1000")
        every { event.componentId } returns InstallWizard.MENU_CATEGORY_STAKES
        every { event.selectedOptions } returns listOf(SelectOption.of("x", InstallWizard.STAKE_ALL_TOKEN))

        menu.handle(ctx, 0)

        val modalSlot = slot<Modal>()
        verify(exactly = 1) { event.replyModal(capture(modalSlot)) }
        assertEquals(expectedId, modalSlot.captured.id)
        // Should not have opened any individual game modal.
        verify(exactly = 0) { stakes.buildModal(any<String>(), any<(Configurations) -> String?>()) }
    }

    @Test
    fun `unknown game token in stakes sub-menu replies ephemerally`() {
        every { event.componentId } returns InstallWizard.MENU_CATEGORY_STAKES
        every { event.selectedOptions } returns listOf(SelectOption.of("x", "bingo"))

        menu.handle(ctx, 0)

        verify(exactly = 1) { event.reply(any<String>()) }
        verify(exactly = 0) { event.replyModal(any<Modal>()) }
    }

    @Test
    fun `quick_channels token opens the EntitySelectMenu modal`() {
        val expectedId = InstallQuickChannelsModal.MODAL_NAME
        val built = mockk<Modal>(relaxed = true) { every { id } returns expectedId }
        every { quickChannels.buildModal() } returns built
        every { event.componentId } returns InstallWizard.sectionDetailMenuId(WizardSection.GENERAL.id)
        every { event.selectedOptions } returns listOf(SelectOption.of("x", QUICK_CHANNELS_TOKEN))

        menu.handle(ctx, 0)

        val modalSlot = slot<Modal>()
        verify(exactly = 1) { event.replyModal(capture(modalSlot)) }
        assertEquals(expectedId, modalSlot.captured.id)
    }

    @Test
    fun `unknown category token in section detail replies ephemerally`() {
        every { event.componentId } returns InstallWizard.sectionDetailMenuId(WizardSection.GENERAL.id)
        every { event.selectedOptions } returns listOf(SelectOption.of("x", "totally_made_up"))

        menu.handle(ctx, 0)

        verify(exactly = 1) { event.reply(any<String>()) }
        verify(exactly = 0) { event.replyModal(any<Modal>()) }
    }
}
