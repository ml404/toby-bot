package bot.toby.install.menu

import bot.toby.command.commands.moderation.SetConfigCommand
import bot.toby.install.ACTIVITY_QUICK_CHANNELS_TOKEN
import bot.toby.install.InstallWizard
import bot.toby.install.JACKPOT_QUICK_CHANNELS_TOKEN
import bot.toby.install.LOTTERY_QUICK_CHANNELS_TOKEN
import bot.toby.install.QUICK_CHANNELS_TOKEN
import bot.toby.install.WizardSection
import bot.toby.install.modal.InstallActivityChannelsModal
import bot.toby.install.modal.InstallAllStakesModal
import bot.toby.install.modal.InstallJackpotChannelsModal
import bot.toby.install.modal.InstallLotteryChannelsModal
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
import database.dto.guild.ConfigDto
import database.dto.guild.ConfigDto.Configurations
import database.service.guild.ConfigService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.selections.SelectOption
import net.dv8tion.jda.api.components.selections.StringSelectMenu
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.modals.Modal
import net.dv8tion.jda.api.requests.restaction.MessageEditAction
import net.dv8tion.jda.api.requests.restaction.interactions.MessageEditCallbackAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import java.util.function.Consumer
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
    private lateinit var jackpotChannels: InstallJackpotChannelsModal
    private lateinit var lotteryChannels: InstallLotteryChannelsModal
    private lateinit var activityChannels: InstallActivityChannelsModal
    private lateinit var menu: InstallCategoryMenu

    private lateinit var event: StringSelectInteractionEvent
    private lateinit var ctx: MenuContext
    private lateinit var guild: Guild
    private lateinit var member: Member
    private lateinit var message: Message
    private lateinit var messageEditAction: MessageEditAction
    private lateinit var deferEditAction: MessageEditCallbackAction

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
        jackpotChannels = mockk(relaxed = true)
        lotteryChannels = mockk(relaxed = true)
        activityChannels = mockk(relaxed = true)
        menu = InstallCategoryMenu(
            configService,
            general, activity, fees, jackpot, jackpotActivity,
            pokerStakes, pokerTable, blackjackRules, blackjackTable,
            lotteryBasics, lotteryPools, stakes, allStakes,
            quickChannels, jackpotChannels, lotteryChannels, activityChannels,
        )

        event = mockk(relaxed = true)
        guild = mockk(relaxed = true) { every { id } returns "g1" }
        member = mockk(relaxed = true) { every { isOwner } returns true }
        // InstallAuth.requireOwner reads event.member; the legacy ctx.member
        // stub is preserved for handlers that still consult the context.
        every { event.member } returns member
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
        // Source message that handleSectionPick / showStakesGameMenu edit via
        // bot webhook after deferEdit acks the interaction.
        message = mockk(relaxed = true)
        every { event.message } returns message
        messageEditAction = mockk(relaxed = true)
        every { message.editMessageEmbeds(any<MessageEmbed>()) } returns messageEditAction
        every {
            messageEditAction.setComponents(*anyVararg<MessageTopLevelComponent>())
        } returns messageEditAction
        every {
            messageEditAction.setComponents(any<Collection<MessageTopLevelComponent>>())
        } returns messageEditAction
        every { messageEditAction.queue() } just Runs

        // deferEdit's `.queue { ... }` invokes the callback synchronously so
        // tests can assert on the chained message-edit deterministically.
        deferEditAction = mockk(relaxed = true)
        every { event.deferEdit() } returns deferEditAction
        every { deferEditAction.queue(any()) } answers {
            @Suppress("UNCHECKED_CAST")
            (firstArg() as Consumer<net.dv8tion.jda.api.interactions.InteractionHook?>)
                .accept(null)
        }
        every { deferEditAction.queue() } just Runs
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
        verify(exactly = 0) { event.deferEdit() }
        verify(exactly = 0) { message.editMessageEmbeds(any<MessageEmbed>()) }
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
    fun `top-level section pick acks via deferEdit then bot-webhook edits the source message`() {
        every { event.componentId } returns InstallWizard.MENU_SECTION
        every { event.selectedOptions } returns listOf(SelectOption.of("x", WizardSection.POKER.id))

        menu.handle(ctx, 0)

        verify(exactly = 1) { event.deferEdit() }
        verify(exactly = 1) { message.editMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 0) { event.replyModal(any<Modal>()) }
    }

    @Test
    fun `section pick never uses the racy interaction-response edit form`() {
        // Regression guard: the previous implementation called
        // `event.editMessageEmbeds(...)` (interaction-response) which races
        // against DefaultMenuManager's bot-webhook disable-rows edit and
        // could leave the message with stale disabled components. The fix
        // routes through deferEdit + event.message.editMessageEmbeds (both
        // bot-webhook, same rate-limit bucket → serialized).
        every { event.componentId } returns InstallWizard.MENU_SECTION
        every { event.selectedOptions } returns listOf(SelectOption.of("x", WizardSection.POKER.id))

        menu.handle(ctx, 0)

        verify(exactly = 0) { event.editMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `section pick orders deferEdit before the message edit`() {
        every { event.componentId } returns InstallWizard.MENU_SECTION
        every { event.selectedOptions } returns listOf(SelectOption.of("x", WizardSection.POKER.id))

        menu.handle(ctx, 0)

        // Documents the serialization contract: deferEdit fires first to
        // ack the interaction, the message edit fires from inside its
        // callback so it lands after the manager's earlier disable-edit.
        verifyOrder {
            event.deferEdit()
            message.editMessageEmbeds(any<MessageEmbed>())
        }
    }

    @Test
    fun `section pick edits the source message instead of replying or opening a modal`() {
        every { event.componentId } returns InstallWizard.MENU_SECTION
        every { event.selectedOptions } returns listOf(SelectOption.of("x", WizardSection.POKER.id))

        menu.handle(ctx, 0)

        verify(exactly = 0) { event.reply(any<String>()) }
        verify(exactly = 0) { event.replyModal(any<Modal>()) }
    }

    @Test
    fun `section pick swaps in the section-detail menu, not the section menu`() {
        // The bug presented exactly as: embed updated, dropdown stayed as
        // "Pick a section to tune" (section menu) and bottom row stayed as
        // Optional features + Finish. This test captures the actual
        // components passed to the bot-webhook edit and asserts the
        // section-detail menu + Back/Finish row are present.
        val captured = slot<Array<MessageTopLevelComponent>>()
        every {
            messageEditAction.setComponents(*varargAll<MessageTopLevelComponent> { true })
        } answers {
            @Suppress("UNCHECKED_CAST")
            captured.captured = args.firstOrNull() as Array<MessageTopLevelComponent>
            messageEditAction
        }
        every { event.componentId } returns InstallWizard.MENU_SECTION
        every { event.selectedOptions } returns listOf(SelectOption.of("x", WizardSection.POKER.id))

        menu.handle(ctx, 0)

        val rows = captured.captured.filterIsInstance<ActionRow>()
        assertEquals(2, rows.size, "section detail view has menu + bottom row")
        val firstRow = rows[0].components.filterIsInstance<StringSelectMenu>()
        assertEquals(1, firstRow.size, "first row is a select menu")
        assertEquals(
            InstallWizard.sectionDetailMenuId(WizardSection.POKER.id),
            firstRow[0].customId,
            "dropdown is the section-detail menu, not the section menu",
        )
        val bottomButtons = rows[1].components.filterIsInstance<Button>().map { it.customId }
        assertTrue(bottomButtons.contains(InstallWizard.BTN_BACK), "bottom row has ← Back")
        assertTrue(bottomButtons.contains(InstallWizard.BTN_FINISH), "bottom row has Finish")
    }

    @Test
    fun `section pick does not leak the previous Optional features button`() {
        // Specific regression: the stale customRoot bottom row had the
        // `install_features` button. After the fix, the bottom row is
        // backAndFinishRow and must not contain that button id.
        val captured = slot<Array<MessageTopLevelComponent>>()
        every {
            messageEditAction.setComponents(*varargAll<MessageTopLevelComponent> { true })
        } answers {
            @Suppress("UNCHECKED_CAST")
            captured.captured = args.firstOrNull() as Array<MessageTopLevelComponent>
            messageEditAction
        }
        every { event.componentId } returns InstallWizard.MENU_SECTION
        every { event.selectedOptions } returns listOf(SelectOption.of("x", WizardSection.GENERAL.id))

        menu.handle(ctx, 0)

        val allButtonIds = captured.captured.filterIsInstance<ActionRow>()
            .flatMap { it.components.filterIsInstance<Button>() }
            .map { it.customId }
        assertFalse(
            allButtonIds.contains(InstallWizard.BTN_FEATURES),
            "Optional features button must not leak into the section-detail view",
        )
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
        every { general.buildModal(SetConfigGeneralModal.MODAL_NAME, any(), any()) } returns built
        every { activity.buildModal(SetConfigActivityModal.MODAL_NAME, any(), any()) } returns built
        every { fees.buildModal(SetConfigFeesModal.MODAL_NAME, any(), any()) } returns built
        every { jackpot.buildModal(SetConfigJackpotModal.MODAL_NAME, any(), any()) } returns built
        every { jackpotActivity.buildModal(SetConfigJackpotActivityModal.MODAL_NAME, any(), any()) } returns built
        every { pokerStakes.buildModal(SetConfigPokerStakesModal.MODAL_NAME, any(), any()) } returns built
        every { pokerTable.buildModal(SetConfigPokerTableModal.MODAL_NAME, any(), any()) } returns built
        every { blackjackRules.buildModal(SetConfigBlackjackRulesModal.MODAL_NAME, any(), any()) } returns built
        every { blackjackTable.buildModal(SetConfigBlackjackTableModal.MODAL_NAME, any(), any()) } returns built
        every { lotteryBasics.buildModal(SetConfigLotteryBasicsModal.MODAL_NAME, any(), any()) } returns built
        every { lotteryPools.buildModal(SetConfigLotteryPoolsModal.MODAL_NAME, any(), any()) } returns built

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
        every { general.buildModal(SetConfigGeneralModal.MODAL_NAME, any(), capture(readerSlot)) } returns built
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

        verify(exactly = 1) { event.deferEdit() }
        verify(exactly = 1) { message.editMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 0) { event.replyModal(any<Modal>()) }
    }

    @Test
    fun `stakes category never uses the racy interaction-response edit form`() {
        every { event.componentId } returns InstallWizard.sectionDetailMenuId(WizardSection.ECONOMY.id)
        every { event.selectedOptions } returns listOf(SelectOption.of("x", SetConfigCommand.SUB_STAKES))

        menu.handle(ctx, 0)

        verify(exactly = 0) { event.editMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `stakes category orders deferEdit before the message edit`() {
        every { event.componentId } returns InstallWizard.sectionDetailMenuId(WizardSection.ECONOMY.id)
        every { event.selectedOptions } returns listOf(SelectOption.of("x", SetConfigCommand.SUB_STAKES))

        menu.handle(ctx, 0)

        verifyOrder {
            event.deferEdit()
            message.editMessageEmbeds(any<MessageEmbed>())
        }
    }

    @Test
    fun `stakes category swaps to the install_category_stakes menu plus back+finish row`() {
        val captured = slot<Array<MessageTopLevelComponent>>()
        every {
            messageEditAction.setComponents(*varargAll<MessageTopLevelComponent> { true })
        } answers {
            @Suppress("UNCHECKED_CAST")
            captured.captured = args.firstOrNull() as Array<MessageTopLevelComponent>
            messageEditAction
        }
        every { event.componentId } returns InstallWizard.sectionDetailMenuId(WizardSection.ECONOMY.id)
        every { event.selectedOptions } returns listOf(SelectOption.of("x", SetConfigCommand.SUB_STAKES))

        menu.handle(ctx, 0)

        val rows = captured.captured.filterIsInstance<ActionRow>()
        assertEquals(2, rows.size)
        val gameMenu = rows[0].components.filterIsInstance<StringSelectMenu>().single()
        assertEquals(InstallWizard.MENU_CATEGORY_STAKES, gameMenu.customId)
        val bottomButtons = rows[1].components.filterIsInstance<Button>().map { it.customId }
        assertTrue(bottomButtons.contains(InstallWizard.BTN_BACK))
        assertTrue(bottomButtons.contains(InstallWizard.BTN_FINISH))
    }

    @ParameterizedTest(name = "section pick {0} lands the right section-detail dropdown")
    @EnumSource(WizardSection::class)
    fun `section pick is parametrised across every WizardSection`(section: WizardSection) {
        val captured = slot<Array<MessageTopLevelComponent>>()
        every {
            messageEditAction.setComponents(*varargAll<MessageTopLevelComponent> { true })
        } answers {
            @Suppress("UNCHECKED_CAST")
            captured.captured = args.firstOrNull() as Array<MessageTopLevelComponent>
            messageEditAction
        }
        every { event.componentId } returns InstallWizard.MENU_SECTION
        every { event.selectedOptions } returns listOf(SelectOption.of("x", section.id))

        menu.handle(ctx, 0)

        val firstRow = captured.captured.filterIsInstance<ActionRow>().first()
        val dropdown = firstRow.components.filterIsInstance<StringSelectMenu>().single()
        assertEquals(
            InstallWizard.sectionDetailMenuId(section.id),
            dropdown.customId,
            "picking ${section.name} must land its detail menu",
        )
    }

    @ParameterizedTest
    @EnumSource(SetConfigStakesModal.Game::class)
    fun `game token in stakes sub-menu opens that game's stakes modal`(game: SetConfigStakesModal.Game) {
        val expectedId = SetConfigStakesModal.customIdFor(game)
        val built = mockk<Modal>(relaxed = true) { every { id } returns expectedId }
        every { stakes.buildModal(expectedId, any(), any()) } returns built
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
        every { event.componentId } returns InstallWizard.MENU_CATEGORY_STAKES
        every { event.selectedOptions } returns listOf(SelectOption.of("x", InstallWizard.STAKE_ALL_TOKEN))

        menu.handle(ctx, 0)

        val modalSlot = slot<Modal>()
        verify(exactly = 1) { event.replyModal(capture(modalSlot)) }
        assertEquals(expectedId, modalSlot.captured.id)
        // Should not have opened any individual game modal.
        verify(exactly = 0) { stakes.buildModal(any<String>(), any(), any<(Configurations) -> String?>()) }
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
    fun `jackpot_quick_channels token opens the jackpot channels modal`() {
        val expectedId = InstallJackpotChannelsModal.MODAL_NAME
        val built = mockk<Modal>(relaxed = true) { every { id } returns expectedId }
        every { jackpotChannels.buildModal() } returns built
        every { event.componentId } returns InstallWizard.sectionDetailMenuId(WizardSection.ECONOMY.id)
        every { event.selectedOptions } returns listOf(SelectOption.of("x", JACKPOT_QUICK_CHANNELS_TOKEN))

        menu.handle(ctx, 0)

        val modalSlot = slot<Modal>()
        verify(exactly = 1) { event.replyModal(capture(modalSlot)) }
        assertEquals(expectedId, modalSlot.captured.id)
        // The siblings must not have been opened.
        verify(exactly = 0) { quickChannels.buildModal() }
        verify(exactly = 0) { lotteryChannels.buildModal() }
    }

    @Test
    fun `lottery_quick_channels token opens the lottery channels modal`() {
        val expectedId = InstallLotteryChannelsModal.MODAL_NAME
        val built = mockk<Modal>(relaxed = true) { every { id } returns expectedId }
        every { lotteryChannels.buildModal() } returns built
        every { event.componentId } returns InstallWizard.sectionDetailMenuId(WizardSection.LOTTERY.id)
        every { event.selectedOptions } returns listOf(SelectOption.of("x", LOTTERY_QUICK_CHANNELS_TOKEN))

        menu.handle(ctx, 0)

        val modalSlot = slot<Modal>()
        verify(exactly = 1) { event.replyModal(capture(modalSlot)) }
        assertEquals(expectedId, modalSlot.captured.id)
        verify(exactly = 0) { quickChannels.buildModal() }
        verify(exactly = 0) { jackpotChannels.buildModal() }
    }

    @Test
    fun `activity_quick_channels token opens the activity channels modal`() {
        val expectedId = InstallActivityChannelsModal.MODAL_NAME
        val built = mockk<Modal>(relaxed = true) { every { id } returns expectedId }
        every { activityChannels.buildModal() } returns built
        every { event.componentId } returns InstallWizard.sectionDetailMenuId(WizardSection.ACTIVITY.id)
        every { event.selectedOptions } returns listOf(SelectOption.of("x", ACTIVITY_QUICK_CHANNELS_TOKEN))

        menu.handle(ctx, 0)

        val modalSlot = slot<Modal>()
        verify(exactly = 1) { event.replyModal(capture(modalSlot)) }
        assertEquals(expectedId, modalSlot.captured.id)
        verify(exactly = 0) { quickChannels.buildModal() }
        verify(exactly = 0) { jackpotChannels.buildModal() }
        verify(exactly = 0) { lotteryChannels.buildModal() }
    }

    @Test
    fun `unknown category token in section detail replies ephemerally`() {
        every { event.componentId } returns InstallWizard.sectionDetailMenuId(WizardSection.GENERAL.id)
        every { event.selectedOptions } returns listOf(SelectOption.of("x", "totally_made_up"))

        menu.handle(ctx, 0)

        verify(exactly = 1) { event.reply(any<String>()) }
        verify(exactly = 0) { event.replyModal(any<Modal>()) }
    }

    @Test
    fun `category pick rearms the section detail row before queuing replyModal`() {
        // Regression: on mobile, dismissing the modal via phone-back used to
        // leave the dropdown + Back/Finish row visually locked. Cause: rearm
        // was queued inside replyModal.queue { ... }, one interaction-callback
        // RTT after the manager's disable PATCH (DefaultMenuManager:38). If
        // the user dismissed the modal in that window, the disable
        // MESSAGE_UPDATE propagated to their client over the gateway before
        // the rearm did. Fix: queue the rearm first so both PATCHes ride the
        // same channel-message rate-limit bucket FIFO — disable, then rearm.
        every { general.buildModal(SetConfigGeneralModal.MODAL_NAME, any(), any()) } returns
            mockk<Modal>(relaxed = true) { every { id } returns SetConfigGeneralModal.MODAL_NAME }
        every { event.componentId } returns InstallWizard.sectionDetailMenuId(WizardSection.GENERAL.id)
        every { event.selectedOptions } returns listOf(SelectOption.of("x", SetConfigCommand.SUB_GENERAL))

        menu.handle(ctx, 0)

        verifyOrder {
            message.editMessageEmbeds(any<MessageEmbed>())
            event.replyModal(any<Modal>())
        }
    }
}
