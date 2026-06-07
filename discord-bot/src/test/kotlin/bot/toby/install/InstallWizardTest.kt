package bot.toby.install

import bot.toby.command.commands.moderation.SetConfigCommand
import database.dto.guild.ConfigDto.Configurations
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.buttons.ButtonStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import common.casino.dice.Dice
import common.casino.roulette.Roulette

/**
 * Tests for the [InstallWizard] component factories. Catches accidental
 * ID typos, broken option-value mappings, and embed copy regressions.
 */
internal class InstallWizardTest {

    @Test
    fun `wizardButtons returns the owner buttons plus the public help button in order`() {
        val buttons = InstallWizard.wizardButtons().components.filterIsInstance<Button>()
        assertEquals(4, buttons.size)
        assertEquals(InstallWizard.BTN_EXPRESS, buttons[0].customId)
        assertEquals(InstallWizard.BTN_CUSTOM, buttons[1].customId)
        assertEquals(InstallWizard.BTN_SKIP, buttons[2].customId)
        // The help button is the only non-owner-gated entry point on the welcome message.
        assertEquals(InstallWizard.BTN_HELP, buttons[3].customId)
    }

    @Test
    fun `finishButtonRow contains the finish button only`() {
        val buttons = InstallWizard.finishButtonRow().components.filterIsInstance<Button>()
        assertEquals(1, buttons.size)
        assertEquals(InstallWizard.BTN_FINISH, buttons[0].customId)
    }

    @Test
    fun `backButtonRow contains the back button only`() {
        val buttons = InstallWizard.backButtonRow().components.filterIsInstance<Button>()
        assertEquals(1, buttons.size)
        assertEquals(InstallWizard.BTN_BACK, buttons[0].customId)
    }

    @Test
    fun `customRootBottomRow has features then finish`() {
        val buttons = InstallWizard.customRootBottomRow().components.filterIsInstance<Button>()
        assertEquals(2, buttons.size)
        assertEquals(InstallWizard.BTN_FEATURES, buttons[0].customId)
        assertEquals(InstallWizard.BTN_FINISH, buttons[1].customId)
    }

    @Test
    fun `backAndFinishRow has back then finish`() {
        val buttons = InstallWizard.backAndFinishRow().components.filterIsInstance<Button>()
        assertEquals(2, buttons.size)
        assertEquals(InstallWizard.BTN_BACK, buttons[0].customId)
        assertEquals(InstallWizard.BTN_FINISH, buttons[1].customId)
    }

    @Test
    fun `sectionMenu shows all six sections when all opt-ins on`() {
        val reader: (Configurations) -> String? = { "true" }
        val menu = InstallWizard.sectionMenu(reader)
        assertEquals(InstallWizard.MENU_SECTION, menu.customId)
        assertEquals(WizardSection.entries.size, menu.options.size)
    }

    @Test
    fun `sectionMenu hides gated sections when opt-ins are off`() {
        val reader: (Configurations) -> String? = { null }
        val menu = InstallWizard.sectionMenu(reader)
        val ids = menu.options.map { it.value }
        assertTrue(ids.contains(WizardSection.GENERAL.id))
        assertTrue(ids.contains(WizardSection.ECONOMY.id))
        assertTrue(ids.contains(WizardSection.POKER.id))
        assertTrue(ids.contains(WizardSection.BLACKJACK.id))
        // Activity + Lottery hidden.
        assertEquals(false, ids.contains(WizardSection.ACTIVITY.id))
        assertEquals(false, ids.contains(WizardSection.LOTTERY.id))
    }

    @Test
    fun `sectionDetailMenu has the right componentId and category options`() {
        val section = WizardSection.ECONOMY
        val menu = InstallWizard.sectionDetailMenu(section)
        assertEquals("${InstallWizard.MENU_SECTION_DETAIL_PREFIX}:${section.id}", menu.customId)
        val values = menu.options.map { it.value }
        assertEquals(section.categories.map { it.token }, values)
    }

    @Test
    fun `sectionDetailMenuId encodes the section id`() {
        assertEquals(
            "install_section_detail:poker",
            InstallWizard.sectionDetailMenuId("poker"),
        )
    }

    @Test
    fun `stakesGameMenu puts apply-to-all first then all the games`() {
        val games = listOf("Dice" to "dice", "Slots" to "slots", "Roulette" to "roulette")
        val menu = InstallWizard.stakesGameMenu(games)
        assertEquals(InstallWizard.MENU_CATEGORY_STAKES, menu.customId)
        assertEquals(1 + games.size, menu.options.size)
        assertEquals(InstallWizard.STAKE_ALL_TOKEN, menu.options[0].value)
        assertEquals("dice", menu.options[1].value)
        assertEquals("slots", menu.options[2].value)
        assertEquals("roulette", menu.options[3].value)
    }

    @Test
    fun `toggleRow has one button per OptInFeatures entry in declaration order`() {
        val reader: (Configurations) -> String? = { null }  // all off
        val buttons = InstallWizard.toggleRow(reader).components.filterIsInstance<Button>()
        assertEquals(OptInFeatures.entries.size, buttons.size)
        OptInFeatures.entries.forEachIndexed { i, feature ->
            assertEquals("${InstallWizard.BTN_TOGGLE_PREFIX}:${feature.key.name}", buttons[i].customId)
        }
    }

    @Test
    fun `toggleRow shows ON green and OFF gray styling per current state`() {
        val reader: (Configurations) -> String? = { key ->
            if (key == Configurations.ACTIVITY_TRACKING) "true" else "false"
        }
        val buttons = InstallWizard.toggleRow(reader).components.filterIsInstance<Button>()
        val activity = buttons.first { it.customId?.endsWith(":ACTIVITY_TRACKING") == true }
        val lottery = buttons.first { it.customId?.endsWith(":LOTTERY_DAILY_ENABLED") == true }
        assertEquals(ButtonStyle.SUCCESS, activity.style)
        assertTrue(activity.label.contains("ON"))
        assertEquals(ButtonStyle.SECONDARY, lottery.style)
        assertTrue(lottery.label.contains("OFF"))
    }

    @Test
    fun `toggleRow treats non-true values as off`() {
        val reader: (Configurations) -> String? = { _ -> "FALSE" }
        val buttons = InstallWizard.toggleRow(reader).components.filterIsInstance<Button>()
        buttons.forEach { btn ->
            assertEquals(ButtonStyle.SECONDARY, btn.style)
            assertTrue(btn.label.contains("OFF"))
        }
    }

    @Test
    fun `every welcome embed factory returns a nonempty description`() {
        val embeds = listOf(
            InstallWizard.welcomeEmbed("Guild"),
            InstallWizard.welcomeBackEmbed("Guild"),
            InstallWizard.dmWelcomeEmbed("Guild"),
            InstallWizard.expressDoneEmbed(),
            InstallWizard.customSectionEmbed(),
            InstallWizard.togglesEmbed(),
            InstallWizard.skipDismissedEmbed(),
            InstallWizard.finishDoneEmbed(),
            InstallWizard.stakesGameMenuEmbed(),
            InstallWizard.sectionDetailEmbed(WizardSection.POKER),
        )
        embeds.forEach { embed ->
            assertNotNull(embed.description)
            assertTrue(embed.description!!.isNotBlank())
        }
    }

    @Test
    fun `welcome embed lists key defaults`() {
        // Intentional copy-coupling: the welcome embed exists to tell owners
        // what "Express" will accept, so dropping any of these mentions is a
        // regression the test should catch. If copy is reworded, update these
        // assertions in the same commit — that's the workflow signal.
        val description = InstallWizard.welcomeEmbed("Test").description!!
        assertTrue(description.contains("Activity tracking", ignoreCase = true))
        assertTrue(description.contains("Daily lottery", ignoreCase = true))
        assertTrue(description.contains("OFF"))
    }

    @Test
    fun `dmWelcomeEmbed mentions install command and guild name`() {
        val embed = InstallWizard.dmWelcomeEmbed("Alpha")
        assertTrue(embed.title!!.contains("Alpha"))
        assertTrue(embed.description!!.contains("/install"))
    }

    @Test
    fun `welcome embed mentions the guild name`() {
        val embed = InstallWizard.welcomeEmbed("MyServer")
        assertTrue(embed.title!!.contains("MyServer"))
    }

    @Test
    fun `welcomeBack embed names the guild and reassures settings are kept`() {
        val embed = InstallWizard.welcomeBackEmbed("MyServer")
        assertTrue(embed.title!!.contains("Welcome back"))
        assertTrue(embed.title!!.contains("MyServer"))
        // Returning owners should be told their prior config survived the re-invite.
        assertTrue(embed.description!!.contains("still saved", ignoreCase = true))
    }

    @Test
    fun `done embeds point owners at concrete first actions`() {
        // Onboarding should hand off to something playable, not just /setconfig.
        listOf(InstallWizard.expressDoneEmbed(), InstallWizard.finishDoneEmbed()).forEach { embed ->
            val description = embed.description!!
            assertTrue(description.contains("/help"))
            assertTrue(description.contains("/blackjack"))
            assertTrue(description.contains("/play"))
        }
    }

    @Test
    fun `every category token in WizardSection is a known SetConfig SUB or wizard-internal token`() {
        val knownSubs = setOf(
            SetConfigCommand.SUB_GENERAL, SetConfigCommand.SUB_ACTIVITY, SetConfigCommand.SUB_FEES,
            SetConfigCommand.SUB_JACKPOT, SetConfigCommand.SUB_JACKPOT_ACTIVITY,
            SetConfigCommand.SUB_POKER_STAKES, SetConfigCommand.SUB_POKER_TABLE,
            SetConfigCommand.SUB_BLACKJACK_RULES, SetConfigCommand.SUB_BLACKJACK_TABLE,
            SetConfigCommand.SUB_LOTTERY_BASICS, SetConfigCommand.SUB_LOTTERY_POOLS,
            SetConfigCommand.SUB_STAKES,
        )
        val wizardInternal = setOf(
            bot.toby.install.QUICK_CHANNELS_TOKEN,
            bot.toby.install.JACKPOT_QUICK_CHANNELS_TOKEN,
            bot.toby.install.LOTTERY_QUICK_CHANNELS_TOKEN,
            bot.toby.install.ACTIVITY_QUICK_CHANNELS_TOKEN,
        )
        WizardSection.entries.flatMap { it.categories }.forEach { cat ->
            assertTrue(
                knownSubs.contains(cat.token) || wizardInternal.contains(cat.token),
                "category token '${cat.token}' is not a known SetConfigCommand SUB constant or wizard-internal token",
            )
        }
    }
}
