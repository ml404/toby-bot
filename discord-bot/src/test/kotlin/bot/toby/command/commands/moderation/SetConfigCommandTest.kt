package bot.toby.command.commands.moderation

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.CommandTest.Companion.member
import bot.toby.command.CommandTest.Companion.replyCallbackAction
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.DefaultCommandContext
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
import database.dto.ConfigDto
import database.dto.ConfigDto.Configurations
import database.service.guild.ConfigService
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.modals.Modal
import net.dv8tion.jda.api.requests.restaction.interactions.ModalCallbackAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Routing tests for `/setconfig`. Now that the command opens a modal
 * per subcommand (and the writes happen in the modal handlers), the
 * old per-option upsert tests have migrated into the per-modal test
 * classes under `bot.toby.modal.modals.setconfig`. What remains:
 *
 *  - Owner permission gate
 *  - Each subcommand resolves to the right modal id
 *  - Modal builder is called with a reader that pulls from
 *    `configService.getConfigByName(...)` so admins see-and-edit
 *  - Stakes subcommand encodes the `game` choice into the modal id
 */
internal class SetConfigCommandTest : CommandTest {

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
    private lateinit var command: SetConfigCommand

    private val modalCallback: ModalCallbackAction = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
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
        command = SetConfigCommand(
            configService,
            general, activity, fees, jackpot, jackpotActivity,
            pokerStakes, pokerTable, blackjackRules, blackjackTable,
            lotteryBasics, lotteryPools, stakes,
        )
        // CommandTest's shared stub makes `event.reply(any<String>())`
        // suspend forever via `just awaits`. SetConfigCommand uses
        // event.reply for early-out validation (must NOT deferReply
        // because the happy path is replyModal). Override accordingly.
        every { event.reply(any<String>()) } returns replyCallbackAction
        every { event.replyModal(any<Modal>()) } returns modalCallback
        every { modalCallback.queue() } just runs
        every { member.isOwner } returns true
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearAllMocks()
    }

    // ---- owner gate ----

    @Test
    fun `non-owner gets ephemeral reply and no modal`() {
        every { member.isOwner } returns false
        every { event.subcommandName } returns SetConfigCommand.SUB_GENERAL

        command.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 1) {
            event.reply("This is currently reserved for the owner of the server only, this may change in future")
        }
        verify(exactly = 0) { event.replyModal(any<Modal>()) }
    }

    @Test
    fun `missing subcommand replies and does not open modal`() {
        every { event.subcommandName } returns null

        command.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 0) { event.replyModal(any<Modal>()) }
        verify { event.reply(match<String> { it.contains("subcommand") }) }
    }

    // ---- subcommand routing — one assertion per subcommand ----

    @Test
    fun `general subcommand opens the general modal`() {
        assertOpensModal(
            sub = SetConfigCommand.SUB_GENERAL,
            modalIdExpected = SetConfigGeneralModal.MODAL_NAME,
            stubReturn = { every { general.buildModal(SetConfigGeneralModal.MODAL_NAME, any(), any()) } returns it },
        )
    }

    @Test
    fun `activity subcommand opens the activity modal`() {
        assertOpensModal(
            SetConfigCommand.SUB_ACTIVITY,
            SetConfigActivityModal.MODAL_NAME,
            { every { activity.buildModal(SetConfigActivityModal.MODAL_NAME, any(), any()) } returns it },
        )
    }

    @Test
    fun `fees subcommand opens the fees modal`() {
        assertOpensModal(
            SetConfigCommand.SUB_FEES,
            SetConfigFeesModal.MODAL_NAME,
            { every { fees.buildModal(SetConfigFeesModal.MODAL_NAME, any(), any()) } returns it },
        )
    }

    @Test
    fun `jackpot subcommand opens the jackpot modal`() {
        assertOpensModal(
            SetConfigCommand.SUB_JACKPOT,
            SetConfigJackpotModal.MODAL_NAME,
            { every { jackpot.buildModal(SetConfigJackpotModal.MODAL_NAME, any(), any()) } returns it },
        )
    }

    @Test
    fun `jackpot_activity subcommand opens the jackpot_activity modal`() {
        assertOpensModal(
            SetConfigCommand.SUB_JACKPOT_ACTIVITY,
            SetConfigJackpotActivityModal.MODAL_NAME,
            { every { jackpotActivity.buildModal(SetConfigJackpotActivityModal.MODAL_NAME, any(), any()) } returns it },
        )
    }

    @Test
    fun `poker_stakes subcommand opens the poker_stakes modal`() {
        assertOpensModal(
            SetConfigCommand.SUB_POKER_STAKES,
            SetConfigPokerStakesModal.MODAL_NAME,
            { every { pokerStakes.buildModal(SetConfigPokerStakesModal.MODAL_NAME, any(), any()) } returns it },
        )
    }

    @Test
    fun `poker_table subcommand opens the poker_table modal`() {
        assertOpensModal(
            SetConfigCommand.SUB_POKER_TABLE,
            SetConfigPokerTableModal.MODAL_NAME,
            { every { pokerTable.buildModal(SetConfigPokerTableModal.MODAL_NAME, any(), any()) } returns it },
        )
    }

    @Test
    fun `blackjack_rules subcommand opens the blackjack_rules modal`() {
        assertOpensModal(
            SetConfigCommand.SUB_BLACKJACK_RULES,
            SetConfigBlackjackRulesModal.MODAL_NAME,
            { every { blackjackRules.buildModal(SetConfigBlackjackRulesModal.MODAL_NAME, any(), any()) } returns it },
        )
    }

    @Test
    fun `blackjack_table subcommand opens the blackjack_table modal`() {
        assertOpensModal(
            SetConfigCommand.SUB_BLACKJACK_TABLE,
            SetConfigBlackjackTableModal.MODAL_NAME,
            { every { blackjackTable.buildModal(SetConfigBlackjackTableModal.MODAL_NAME, any(), any()) } returns it },
        )
    }

    @Test
    fun `lottery_basics subcommand opens the lottery_basics modal`() {
        assertOpensModal(
            SetConfigCommand.SUB_LOTTERY_BASICS,
            SetConfigLotteryBasicsModal.MODAL_NAME,
            { every { lotteryBasics.buildModal(SetConfigLotteryBasicsModal.MODAL_NAME, any(), any()) } returns it },
        )
    }

    @Test
    fun `lottery_pools subcommand opens the lottery_pools modal`() {
        assertOpensModal(
            SetConfigCommand.SUB_LOTTERY_POOLS,
            SetConfigLotteryPoolsModal.MODAL_NAME,
            { every { lotteryPools.buildModal(SetConfigLotteryPoolsModal.MODAL_NAME, any(), any()) } returns it },
        )
    }

    @Test
    fun `stakes subcommand encodes the game choice into the modal id`() {
        val expectedId = SetConfigStakesModal.customIdFor(SetConfigStakesModal.Game.DICE)
        assertOpensModal(
            sub = SetConfigCommand.SUB_STAKES,
            modalIdExpected = expectedId,
            stubReturn = { every { stakes.buildModal(expectedId, any(), any()) } returns it },
            extraEventStubs = {
                val opt = mockk<OptionMapping> { every { asString } returns "dice" }
                every { event.getOption(SetConfigCommand.OPT_GAME) } returns opt
            },
        )
    }

    @Test
    fun `stakes subcommand with unknown game replies error and opens no modal`() {
        every { event.subcommandName } returns SetConfigCommand.SUB_STAKES
        val opt = mockk<OptionMapping> { every { asString } returns "bingo" }
        every { event.getOption(SetConfigCommand.OPT_GAME) } returns opt

        command.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 0) { event.replyModal(any<Modal>()) }
        verify { event.reply(match<String> { it.contains("bingo") }) }
    }

    // ---- modal pre-population reader ----

    @Test
    fun `buildModal reader resolves current values via configService getConfigByName`() {
        val readerSlot = slot<(Configurations) -> String?>()
        every { event.subcommandName } returns SetConfigCommand.SUB_GENERAL
        val builtModal = mockk<Modal>(relaxed = true) { every { id } returns SetConfigGeneralModal.MODAL_NAME }
        every { general.buildModal(SetConfigGeneralModal.MODAL_NAME, any(), capture(readerSlot)) } returns builtModal
        // Use any() because a relaxed-mock ConfigService returns a default
        // ConfigDto (value="") for unstubbed calls — the literal-arg stub
        // matcher fights that default.
        every { configService.getConfigByName(any(), any()) } returns
            ConfigDto(Configurations.VOLUME.configValue, "75", "1")

        command.handle(DefaultCommandContext(event), requestingUserDto, 0)

        assertNotNull(readerSlot.captured)
        assertEquals("75", readerSlot.captured(Configurations.VOLUME))
        // Verify the reader actually reached the service with the right key/guild.
        verify { configService.getConfigByName(Configurations.VOLUME.configValue, "1") }
    }

    // ---- helper ----

    private fun assertOpensModal(
        sub: String,
        modalIdExpected: String,
        stubReturn: (Modal) -> Unit,
        extraEventStubs: () -> Unit = {},
    ) {
        every { event.subcommandName } returns sub
        extraEventStubs()
        val captured = slot<Modal>()
        val builtModal = mockk<Modal>(relaxed = true) { every { id } returns modalIdExpected }
        stubReturn(builtModal)
        every { event.replyModal(capture(captured)) } returns modalCallback

        command.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 1) { event.replyModal(any<Modal>()) }
        assertEquals(modalIdExpected, captured.captured.id)
    }
}
