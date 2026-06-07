package bot.toby.install

import database.service.guild.ConfigService
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.selections.SelectOption
import net.dv8tion.jda.api.components.selections.StringSelectMenu
import net.dv8tion.jda.api.entities.MessageEmbed

/**
 * Shared constants + factories for the in-Discord install wizard. Every
 * embed and component used across the wizard's handlers is built here so
 * IDs and copy live in one place.
 *
 * The custom-setup flow is two-tier: a top-level "Section" menu groups
 * the 12 setconfig categories thematically (General, Economy, Poker,
 * Blackjack, Activity, Lottery). Picking a section opens a second menu
 * listing the categories within it. This keeps the dropdown short and
 * lets gated sections (Activity, Lottery) drop out cleanly when their
 * opt-in is off.
 */
object InstallWizard {

    /** Build the standard config-value reader bound to a specific guild. */
    fun configReader(configService: ConfigService, guildId: String): ConfigReader =
        { key -> configService.getConfigByName(key.configValue, guildId)?.value }

    /** Build the two-row component set for the custom-setup root view. */
    fun customRootRows(reader: ConfigReader): List<ActionRow> = listOf(
        ActionRow.of(sectionMenu(reader)),
        customRootBottomRow(),
    )

    // ---- componentId namespace ----
    const val BTN_EXPRESS = "install_express"
    const val BTN_CUSTOM = "install_custom"
    const val BTN_SKIP = "install_skip"
    const val BTN_FINISH = "install_finish"
    const val BTN_BACK = "install_category_back"
    const val BTN_FEATURES = "install_features"
    const val BTN_TOGGLE_PREFIX = "install_toggle"

    const val MENU_SECTION = "install_section"
    const val MENU_SECTION_DETAIL_PREFIX = "install_section_detail"
    const val MENU_CATEGORY_STAKES = "install_category_stakes"

    /** Synthetic stake-modal token for "Apply to all games". */
    const val STAKE_ALL_TOKEN = "all_games"

    fun sectionDetailMenuId(sectionId: String): String = "$MENU_SECTION_DETAIL_PREFIX:$sectionId"

    // ---- embed factories ----

    fun welcomeEmbed(guildName: String): MessageEmbed = EmbedBuilder()
        .setTitle("Thanks for adding me to $guildName! 🎉")
        .setDescription(
            "I'm **toby-bot** — here's what I bring to the table:\n\n" +
                "🎲 **Casino** — blackjack, poker, roulette, slots, dice & more, all sharing a Toby Coin " +
                "economy and a server jackpot\n" +
                "🎵 **Music** — queue and play audio right in your voice channels\n" +
                "📈 **Leveling** — optional XP, leaderboards & activity rewards\n" +
                "🎟️ **Daily lottery** — an optional server-wide draw\n\n" +
                "**Let's get you set up — pick a path below:**\n" +
                "**▸ Express setup** — one click, sensible defaults, start playing right away. Express keeps " +
                "**Activity tracking OFF**, the **Daily lottery OFF**, and uses conservative casino stakes — " +
                "flip anything on later with `/setconfig`.\n" +
                "**▸ Custom setup** — tune audio, channels, casino rules, lottery & more before you start.\n" +
                "**▸ Skip for now** — dismiss this; run `/install` whenever you're ready.\n\n" +
                "💡 Once you're set up, anyone can run `/help`, deal a hand with `/blackjack`, or queue a " +
                "track with `/play`.\n" +
                "_Only the server owner can use these buttons._"
        )
        .build()

    /**
     * Returning-server variant of [welcomeEmbed], shown when the bot is
     * re-invited to a guild it was previously set up in (detected via a
     * surviving `INSTALLED_AT` sentinel). Reassures the owner their prior
     * configuration is intact and offers the same Express / Custom / Skip
     * buttons in case they want to review it.
     */
    fun welcomeBackEmbed(guildName: String): MessageEmbed = EmbedBuilder()
        .setTitle("Welcome back to $guildName! 👋")
        .setDescription(
            "Good to be back — **your previous settings are still saved**, so the casino, channels and " +
                "everything you configured before are exactly as you left them. You don't need to do " +
                "anything to keep them.\n\n" +
                "If you'd like to review or change your setup:\n" +
                "**▸ Express setup** — re-confirm the quick defaults.\n" +
                "**▸ Custom setup** — jump back into the tuning menus.\n" +
                "**▸ Skip for now** — keep everything as it is.\n\n" +
                "💡 Or jump straight back in with `/help`, `/blackjack`, or `/play`.\n" +
                "_Only the server owner can use these buttons._"
        )
        .build()

    /**
     * DM fallback variant of the welcome message — used when the bot has
     * no writable channel in the guild. Discord buttons in a DM can't
     * resolve guild context reliably, so this variant just nudges the
     * owner to run `/install` (or grant the bot a channel) instead of
     * embedding clickable wizard buttons.
     */
    fun dmWelcomeEmbed(guildName: String): MessageEmbed = EmbedBuilder()
        .setTitle("toby-bot joined $guildName")
        .setDescription(
            "Thanks for adding me! I couldn't find a channel I had permission to post in, " +
                "so I'm reaching out here.\n\n" +
                "To finish setup, head into **$guildName** and run **`/install`** — that opens the " +
                "Express / Custom / Skip wizard. If you'd rather I post the wizard publicly, give me " +
                "**View Channel** + **Send Messages** in any channel and re-invite me, or just run " +
                "`/install` from inside the server."
        )
        .build()

    fun expressDoneEmbed(): MessageEmbed = EmbedBuilder()
        .setTitle("You're all set! 🎉")
        .setDescription(
            "Defaults are live and the casino is open. Here's how to get going:\n\n" +
                "• `/help` — browse every command\n" +
                "• `/blackjack` or `/roulette` — deal a quick game\n" +
                "• `/play <song>` — queue music in a voice channel\n\n" +
                "Owners: `/setconfig <category>` fine-tunes any setting, and `/install` reopens this wizard anytime."
        )
        .build()

    fun customSectionEmbed(): MessageEmbed = EmbedBuilder()
        .setTitle("Custom setup")
        .setDescription(
            "Pick a section, then a category within it to edit. Each opens a form pre-filled with the " +
                "current values — leave any field blank to keep it. Click **Finish** when you're done.\n\n" +
                "Click **Optional features** anytime to toggle activity tracking or the daily lottery."
        )
        .build()

    fun sectionDetailEmbed(section: WizardSection): MessageEmbed = EmbedBuilder()
        .setTitle(section.label)
        .setDescription(
            "${section.description}\n\n" +
                "Pick a category to edit. Click **← Back** to return to the section list."
        )
        .build()

    fun stakesGameMenuEmbed(): MessageEmbed = EmbedBuilder()
        .setTitle("Per-game stake bounds")
        .setDescription(
            "Pick **Apply to all games** to write the same min/max to every game in one shot, " +
                "or pick a game to tune its bounds individually. Click **← Back** to return."
        )
        .build()

    fun togglesEmbed(): MessageEmbed = EmbedBuilder()
        .setTitle("Optional features")
        .setDescription(
            "Toggle these on or off:\n\n" +
                OptInFeatures.entries.joinToString("\n") { "• **${it.label}** — ${it.description}" } +
                "\n\nClick **← Back** when you're done."
        )
        .build()

    fun skipDismissedEmbed(): MessageEmbed = EmbedBuilder()
        .setTitle("Install skipped")
        .setDescription("No changes were made. Run `/install` whenever you'd like to come back.")
        .build()

    fun finishDoneEmbed(): MessageEmbed = EmbedBuilder()
        .setTitle("Custom setup complete ✅")
        .setDescription(
            "Your settings are saved. Time to play:\n\n" +
                "• `/help` — browse every command\n" +
                "• `/blackjack` or `/roulette` — deal a quick game\n" +
                "• `/play <song>` — queue music in a voice channel\n\n" +
                "Keep tuning anytime with `/setconfig <category>`, or reopen this wizard with `/install`."
        )
        .build()

    // ---- component factories ----

    fun wizardButtons(): ActionRow = ActionRow.of(
        Button.success(BTN_EXPRESS, "Express setup"),
        Button.primary(BTN_CUSTOM, "Custom setup"),
        Button.secondary(BTN_SKIP, "Skip for now"),
    )

    fun finishButtonRow(): ActionRow = ActionRow.of(
        Button.success(BTN_FINISH, "Finish"),
    )

    fun backButtonRow(): ActionRow = ActionRow.of(
        Button.secondary(BTN_BACK, "← Back"),
    )

    fun customRootBottomRow(): ActionRow = ActionRow.of(
        Button.secondary(BTN_FEATURES, "Optional features"),
        Button.success(BTN_FINISH, "Finish"),
    )

    fun backAndFinishRow(): ActionRow = ActionRow.of(
        Button.secondary(BTN_BACK, "← Back"),
        Button.success(BTN_FINISH, "Finish"),
    )

    /**
     * Top-level section picker. Gated sections (Activity, Lottery) drop
     * out when their respective opt-in is off. Always non-empty: the
     * General/Economy/Poker/Blackjack sections have no gate.
     */
    fun sectionMenu(currentValues: ConfigReader): StringSelectMenu {
        val builder = StringSelectMenu.create(MENU_SECTION).setPlaceholder("Pick a section to tune")
        WizardSection.visibleFor(currentValues).forEach { section ->
            builder.addOptions(
                SelectOption.of(section.label, section.id).withDescription(section.description),
            )
        }
        return builder.build()
    }

    /**
     * Second-tier menu — the categories within a specific section. The
     * componentId includes the section id so the menu handler knows which
     * section we're in (and can rearm appropriately after a modal closes).
     */
    fun sectionDetailMenu(section: WizardSection): StringSelectMenu {
        val builder = StringSelectMenu.create(sectionDetailMenuId(section.id))
            .setPlaceholder("Pick a category to edit")
        section.categories.forEach { cat ->
            builder.addOptions(SelectOption.of(cat.label, cat.token).withDescription(cat.description))
        }
        return builder.build()
    }

    /**
     * Per-game stakes picker. The first entry is "Apply to all games" so
     * owners with uniform stake bounds across games can write them in
     * a single modal. Other entries are the individual game tokens.
     */
    fun stakesGameMenu(games: List<Pair<String, String>>): StringSelectMenu {
        val builder = StringSelectMenu.create(MENU_CATEGORY_STAKES).setPlaceholder("Pick a game (or all)")
        builder.addOptions(
            SelectOption.of("Apply to all games", STAKE_ALL_TOKEN)
                .withDescription("Writes the same min/max to every game in one shot"),
        )
        games.forEach { (label, token) ->
            builder.addOption(label, token)
        }
        return builder.build()
    }

    /**
     * One toggle button per [OptInFeatures] entry, labelled + styled to
     * reflect the supplied current-value reader.
     */
    fun toggleRow(currentValues: ConfigReader): ActionRow {
        val buttons = OptInFeatures.entries.map { feature ->
            val on = currentValues(feature.key) == "true"
            val state = if (on) "ON" else "OFF"
            val id = "$BTN_TOGGLE_PREFIX:${feature.key.name}"
            if (on) Button.success(id, "${feature.label}: $state")
            else Button.secondary(id, "${feature.label}: $state")
        }
        return ActionRow.of(buttons)
    }
}
