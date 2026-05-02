package bot.toby.command.commands.moderation

import bot.toby.activity.ActivityTrackingNotifier
import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.CommandContext
import database.dto.ConfigDto
import database.dto.UserDto
import database.service.ConfigService
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.util.*

@Component
class SetConfigCommand @Autowired constructor(
    private val configService: ConfigService,
    private val activityTrackingNotifier: ActivityTrackingNotifier
) : ModerationCommand {

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply(true).queue()
        val member = ctx.member
        if (member?.isOwner != true) {
            event.hook.sendMessage("This is currently reserved for the owner of the server only, this may change in future")
                .setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }
        validateArgumentsAndUpdateConfigs(event, deleteDelay)
    }

    private fun validateArgumentsAndUpdateConfigs(event: SlashCommandInteractionEvent, deleteDelay: Int) {
        val options = event.options
        if (options.isEmpty()) {
            event.hook.sendMessage(description).queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }
        options.forEach { optionMapping ->
            when (ConfigDto.Configurations.valueOf(optionMapping.name.uppercase(Locale.getDefault()))) {
                ConfigDto.Configurations.MOVE -> setMove(event, deleteDelay)
                ConfigDto.Configurations.VOLUME -> setConfigAndSendMessage(
                    event,
                    optionMapping,
                    deleteDelay,
                    "Set default volume to '${optionMapping.asInt}'"
                )
                ConfigDto.Configurations.DELETE_DELAY -> setConfigAndSendMessage(
                    event,
                    optionMapping,
                    deleteDelay,
                    "Set default delete message delay for TobyBot music messages to '${optionMapping.asInt}' seconds"
                )
                ConfigDto.Configurations.INTRO_VOLUME -> setConfigAndSendMessage(event, optionMapping, deleteDelay,
                    "Set default intro volume to '${optionMapping.asInt}'")
                ConfigDto.Configurations.LEADERBOARD_CHANNEL -> setLeaderboardChannel(event, deleteDelay)
                ConfigDto.Configurations.ACTIVITY_TRACKING -> setActivityTracking(event, optionMapping, deleteDelay)
                ConfigDto.Configurations.ACTIVITY_TRACKING_NOTIFIED -> Unit
                ConfigDto.Configurations.JACKPOT_LOSS_TRIBUTE_PCT -> setJackpotLossTribute(event, optionMapping, deleteDelay)
                ConfigDto.Configurations.JACKPOT_WIN_PCT -> setJackpotWinPct(event, optionMapping, deleteDelay)
                ConfigDto.Configurations.TRADE_BUY_FEE_PCT -> setTradeFeePct(
                    event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.TRADE_BUY_FEE_PCT, label = "buy"
                )
                ConfigDto.Configurations.TRADE_SELL_FEE_PCT -> setTradeFeePct(
                    event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.TRADE_SELL_FEE_PCT, label = "sell"
                )
                ConfigDto.Configurations.POKER_RAKE_PCT -> setPokerRake(event, optionMapping, deleteDelay)
                ConfigDto.Configurations.POKER_SMALL_BLIND -> setMinimumLongConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.POKER_SMALL_BLIND, gameLabel = "Poker", label = "small blind", min = 1L, unit = "chips")
                ConfigDto.Configurations.POKER_BIG_BLIND -> setMinimumLongConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.POKER_BIG_BLIND, gameLabel = "Poker", label = "big blind", min = 1L, unit = "chips")
                ConfigDto.Configurations.POKER_SMALL_BET -> setMinimumLongConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.POKER_SMALL_BET, gameLabel = "Poker", label = "small bet", min = 1L, unit = "chips")
                ConfigDto.Configurations.POKER_BIG_BET -> setMinimumLongConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.POKER_BIG_BET, gameLabel = "Poker", label = "big bet", min = 1L, unit = "chips")
                ConfigDto.Configurations.POKER_MIN_BUY_IN -> setMinimumLongConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.POKER_MIN_BUY_IN, gameLabel = "Poker", label = "minimum buy-in", min = 1L, unit = "chips")
                ConfigDto.Configurations.POKER_MAX_BUY_IN -> setMinimumLongConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.POKER_MAX_BUY_IN, gameLabel = "Poker", label = "maximum buy-in", min = 1L, unit = "chips")
                ConfigDto.Configurations.POKER_MAX_SEATS -> setRangedIntConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.POKER_MAX_SEATS, gameLabel = "Poker", label = "max seats", range = 2..9)
                ConfigDto.Configurations.POKER_SHOT_CLOCK_SECONDS -> setRangedIntConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.POKER_SHOT_CLOCK_SECONDS, gameLabel = "Poker",
                    label = "shot-clock seconds", range = 0..600)
                ConfigDto.Configurations.BLACKJACK_RAKE_PCT -> setRangedIntConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.BLACKJACK_RAKE_PCT, gameLabel = "Blackjack", label = "rake percent", range = 0..20)
                ConfigDto.Configurations.BLACKJACK_MIN_ANTE -> setMinimumLongConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.BLACKJACK_MIN_ANTE, gameLabel = "Blackjack", label = "minimum ante", min = 1L, unit = "credits")
                ConfigDto.Configurations.BLACKJACK_MAX_ANTE -> setMinimumLongConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.BLACKJACK_MAX_ANTE, gameLabel = "Blackjack", label = "maximum ante", min = 1L, unit = "credits")
                ConfigDto.Configurations.BLACKJACK_MAX_SEATS -> setRangedIntConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.BLACKJACK_MAX_SEATS, gameLabel = "Blackjack", label = "max seats", range = 2..7)
                ConfigDto.Configurations.BLACKJACK_SHOT_CLOCK_SECONDS -> setRangedIntConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.BLACKJACK_SHOT_CLOCK_SECONDS, gameLabel = "Blackjack",
                    label = "shot-clock seconds", range = 0..600)
                ConfigDto.Configurations.BLACKJACK_DEALER_HITS_SOFT_17 -> setBlackjackBool(event, optionMapping, deleteDelay)
                ConfigDto.Configurations.BLACKJACK_BJ_PAYOUT_NUM -> setRangedIntConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.BLACKJACK_BJ_PAYOUT_NUM, gameLabel = "Blackjack",
                    label = "blackjack payout numerator", range = 1..10)
                ConfigDto.Configurations.BLACKJACK_BJ_PAYOUT_DEN -> setRangedIntConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.BLACKJACK_BJ_PAYOUT_DEN, gameLabel = "Blackjack",
                    label = "blackjack payout denominator", range = 1..10)
                ConfigDto.Configurations.UBI_DAILY_AMOUNT -> setUbiDailyAmount(event, optionMapping, deleteDelay)
                ConfigDto.Configurations.DAILY_CREDIT_CAP -> setDailyCreditCap(event, optionMapping, deleteDelay)
            }
        }
    }

    /**
     * Validate an integer config knob within an inclusive [range],
     * persist it, and reply with a uniform "$gameLabel $label set to $value
     * for new tables." message. Generic across the per-game integer
     * settings (poker max seats, poker shot clock, blackjack max seats,
     * blackjack rake percent, blackjack BJ payout num/den, etc.).
     */
    private fun setRangedIntConfig(
        event: SlashCommandInteractionEvent,
        optionMapping: OptionMapping,
        deleteDelay: Int,
        config: ConfigDto.Configurations,
        gameLabel: String,
        label: String,
        range: IntRange,
    ) {
        val value = optionMapping.asInt
        if (value !in range) {
            event.hook.sendMessage("$gameLabel $label must be between ${range.first} and ${range.last}.")
                .setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }
        val guildId = event.guild?.id ?: return
        configService.upsertConfig(config.configValue, value.toString(), guildId)
        event.hook.sendMessage("$gameLabel $label set to $value for new tables.")
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    /**
     * Validate a non-negative whole-number config knob with a floor of
     * [min], persist it, and reply with a "$gameLabel $label set to
     * $value $unit for new tables." message. Generic across poker chip
     * thresholds (chips) and blackjack ante bounds (credits).
     */
    private fun setMinimumLongConfig(
        event: SlashCommandInteractionEvent,
        optionMapping: OptionMapping,
        deleteDelay: Int,
        config: ConfigDto.Configurations,
        gameLabel: String,
        label: String,
        min: Long,
        unit: String,
    ) {
        val value = optionMapping.asLong
        if (value < min) {
            event.hook.sendMessage("$gameLabel $label must be at least $min.")
                .setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }
        val guildId = event.guild?.id ?: return
        configService.upsertConfig(config.configValue, value.toString(), guildId)
        event.hook.sendMessage("$gameLabel $label set to $value $unit for new tables.")
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun setBlackjackBool(
        event: SlashCommandInteractionEvent,
        optionMapping: OptionMapping,
        deleteDelay: Int
    ) {
        val value = optionMapping.asBoolean
        val configValue = ConfigDto.Configurations.BLACKJACK_DEALER_HITS_SOFT_17.configValue
        val guildId = event.guild?.id ?: return
        configService.upsertConfig(configValue, value.toString(), guildId)
        val rule = if (value) "hit on soft 17 (H17)" else "stand on all 17 (S17)"
        event.hook.sendMessage("Blackjack dealer will now $rule.")
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun setLeaderboardChannel(event: SlashCommandInteractionEvent, deleteDelay: Int) {
        val configValue = ConfigDto.Configurations.LEADERBOARD_CHANNEL.configValue
        val guildId = event.guild?.id ?: return
        val channel = event.getOption(
            ConfigDto.Configurations.LEADERBOARD_CHANNEL.name.lowercase(Locale.getDefault())
        )?.asChannel
        if (channel == null) {
            event.hook.sendMessage("No valid text channel was mentioned, so config was not updated")
                .setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }
        configService.upsertConfig(configValue, channel.id, guildId)
        event.hook.sendMessage("Monthly leaderboards will now post in <#${channel.id}> on the 1st of each month.")
            .setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun setActivityTracking(
        event: SlashCommandInteractionEvent,
        optionMapping: OptionMapping,
        deleteDelay: Int
    ) {
        val enabled = optionMapping.asBoolean
        val configValue = ConfigDto.Configurations.ACTIVITY_TRACKING.configValue
        val guildId = event.guild?.id ?: return

        // Need to know whether tracking was previously enabled to decide
        // whether to fire the first-enable DM flow — that's why we branch
        // on the result instead of ignoring it.
        val result = configService.upsertConfig(configValue, enabled.toString(), guildId)
        val previouslyEnabled = when (result) {
            is ConfigService.UpsertResult.Created -> false
            is ConfigService.UpsertResult.Updated ->
                result.previousValue?.equals("true", ignoreCase = true) == true
        }

        val message = if (enabled) {
            "Enabled game-activity tracking for this server. Members will only be tracked while they have set " +
                    "their Discord activity visibility to allow it. Any member can opt out with `/activity tracking-off`."
        } else {
            "Disabled game-activity tracking for this server. Existing rollups are retained but no new activity " +
                    "will be recorded."
        }
        event.hook.sendMessage(message)
            .setEphemeral(true)
            .queue(invokeDeleteOnMessageResponse(deleteDelay))

        if (enabled && !previouslyEnabled) {
            event.guild?.let { activityTrackingNotifier.notifyMembersOnFirstEnable(it) }
        }
    }

    private fun setConfigAndSendMessage(
        event: SlashCommandInteractionEvent,
        optionMapping: OptionMapping,
        deleteDelay: Int,
        messageToSend: String
    ) {
        val newValue = optionMapping.asInt.takeIf { it >= 0 }
        if (newValue == null) {
            event.hook.sendMessage("Value given invalid (a whole number representing percent)")
                .setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }

        val configValue =
            ConfigDto.Configurations.valueOf(optionMapping.name.uppercase(Locale.getDefault())).configValue
        val guildId = event.guild?.id ?: return

        configService.upsertConfig(configValue, newValue.toString(), guildId)
        event.hook.sendMessage(messageToSend).queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun setJackpotLossTribute(
        event: SlashCommandInteractionEvent,
        optionMapping: OptionMapping,
        deleteDelay: Int
    ) {
        val pct = optionMapping.asInt
        if (pct !in 0..50) {
            event.hook.sendMessage("Tribute percent must be between 0 and 50 (default 10).")
                .setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }
        val configValue = ConfigDto.Configurations.JACKPOT_LOSS_TRIBUTE_PCT.configValue
        val guildId = event.guild?.id ?: return
        configService.upsertConfig(configValue, pct.toString(), guildId)
        event.hook.sendMessage("Jackpot loss-tribute set to $pct % of every lost casino stake.")
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun setTradeFeePct(
        event: SlashCommandInteractionEvent,
        optionMapping: OptionMapping,
        deleteDelay: Int,
        config: ConfigDto.Configurations,
        label: String
    ) {
        val pct = optionMapping.asDouble
        if (pct.isNaN() || pct.isInfinite() || pct < 0.0 || pct > 25.0) {
            event.hook.sendMessage("Trade $label fee percent must be between 0 and 25 (default 1).")
                .setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }
        val guildId = event.guild?.id ?: return
        configService.upsertConfig(config.configValue, pct.toString(), guildId)
        event.hook.sendMessage("Toby Coin $label fee set to $pct % per leg (routed into the jackpot pool).")
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun setJackpotWinPct(
        event: SlashCommandInteractionEvent,
        optionMapping: OptionMapping,
        deleteDelay: Int
    ) {
        val pct = optionMapping.asDouble
        if (pct.isNaN() || pct.isInfinite() || pct < 0.0 || pct > 50.0) {
            event.hook.sendMessage("Jackpot win percent must be between 0 and 50 (default 1).")
                .setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }
        val configValue = ConfigDto.Configurations.JACKPOT_WIN_PCT.configValue
        val guildId = event.guild?.id ?: return
        configService.upsertConfig(configValue, pct.toString(), guildId)
        event.hook.sendMessage("Jackpot win chance set to $pct % per casino-game win.")
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun setPokerRake(
        event: SlashCommandInteractionEvent,
        optionMapping: OptionMapping,
        deleteDelay: Int
    ) {
        val pct = optionMapping.asInt
        if (pct !in 0..20) {
            event.hook.sendMessage("Poker rake must be between 0 and 20 (default 5).")
                .setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }
        val configValue = ConfigDto.Configurations.POKER_RAKE_PCT.configValue
        val guildId = event.guild?.id ?: return
        configService.upsertConfig(configValue, pct.toString(), guildId)
        event.hook.sendMessage("Poker rake set to $pct % of every settled pot.")
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun setUbiDailyAmount(
        event: SlashCommandInteractionEvent,
        optionMapping: OptionMapping,
        deleteDelay: Int
    ) {
        val amount = optionMapping.asInt
        if (amount !in 0..1000) {
            event.hook.sendMessage("UBI daily amount must be between 0 and 1000 (0 disables UBI).")
                .setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }
        val configValue = ConfigDto.Configurations.UBI_DAILY_AMOUNT.configValue
        val guildId = event.guild?.id ?: return
        configService.upsertConfig(configValue, amount.toString(), guildId)
        val message = if (amount == 0) {
            "UBI disabled — no automatic daily credits will be granted."
        } else {
            "UBI set to $amount credits per user per day. The grant runs at 00:00 UTC and bypasses the daily cap."
        }
        event.hook.sendMessage(message).queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun setDailyCreditCap(
        event: SlashCommandInteractionEvent,
        optionMapping: OptionMapping,
        deleteDelay: Int
    ) {
        val cap = optionMapping.asInt
        if (cap !in 0..10000) {
            event.hook.sendMessage("Daily credit cap must be between 0 and 10000 (default 90).")
                .setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }
        val configValue = ConfigDto.Configurations.DAILY_CREDIT_CAP.configValue
        val guildId = event.guild?.id ?: return
        configService.upsertConfig(configValue, cap.toString(), guildId)
        event.hook.sendMessage(
            "Daily social-credit cap set to $cap credits (covers voice, command, intro, and UI-trade earnings)."
        ).queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun setMove(event: SlashCommandInteractionEvent, deleteDelay: Int) {
        val movePropertyName = ConfigDto.Configurations.MOVE.configValue
        val guildId = event.guild?.id ?: return
        val newDefaultMoveChannel =
            event.getOption(ConfigDto.Configurations.MOVE.name.lowercase(Locale.getDefault()))?.asChannel

        if (newDefaultMoveChannel != null) {
            configService.upsertConfig(movePropertyName, newDefaultMoveChannel.name, guildId)
            event.hook.sendMessage("Set default move channel to '${newDefaultMoveChannel.name}'")
                .setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay))
        } else {
            event.hook.sendMessage("No valid channel was mentioned, so config was not updated")
                .setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay))
        }
    }

    override val name: String
        get() = "setconfig"

    override val description: String
        get() = "Use this command to set the configuration used for this bot in your server"

    override val optionData: List<OptionData>
        get() = listOf(
            OptionData(
                OptionType.INTEGER,
                ConfigDto.Configurations.VOLUME.name.lowercase(Locale.getDefault()),
                "Default volume for audio player on your server (100 without an override)",
                false
            ),
            OptionData(
                OptionType.INTEGER,
                ConfigDto.Configurations.INTRO_VOLUME.name.lowercase(Locale.getDefault()),
                "Default volume of intros for users on your server (90 without an override)",
                false
            ),
            OptionData(
                OptionType.INTEGER,
                ConfigDto.Configurations.DELETE_DELAY.name.lowercase(Locale.getDefault()),
                "The length of time in seconds before your slash command messages are deleted",
                false
            ),
            OptionData(
                OptionType.CHANNEL,
                ConfigDto.Configurations.MOVE.name.lowercase(Locale.getDefault()),
                "Value for the default move channel you want if using move command without arguments",
                false
            ),
            OptionData(
                OptionType.CHANNEL,
                ConfigDto.Configurations.LEADERBOARD_CHANNEL.name.lowercase(Locale.getDefault()),
                "Text channel for the monthly social credit leaderboard post",
                false
            ).setChannelTypes(ChannelType.TEXT),
            OptionData(
                OptionType.BOOLEAN,
                ConfigDto.Configurations.ACTIVITY_TRACKING.name.lowercase(Locale.getDefault()),
                "Enable game-activity tracking in this server (users can opt out individually)",
                false
            ),
            OptionData(
                OptionType.INTEGER,
                ConfigDto.Configurations.JACKPOT_LOSS_TRIBUTE_PCT.name.lowercase(Locale.getDefault()),
                "Percent (0-50) of every lost casino stake routed into the per-guild jackpot pool. Default 10.",
                false
            ),
            OptionData(
                OptionType.NUMBER,
                ConfigDto.Configurations.JACKPOT_WIN_PCT.name.lowercase(Locale.getDefault()),
                "Percent (0-50, decimals e.g. 0.5) chance any casino-game win triggers the jackpot. Default 1.",
                false
            ),
            OptionData(
                OptionType.NUMBER,
                ConfigDto.Configurations.TRADE_BUY_FEE_PCT.name.lowercase(Locale.getDefault()),
                "Percent (0-25, decimals allowed) skimmed off every Toby Coin BUY into the jackpot pool. Default 1.",
                false
            ),
            OptionData(
                OptionType.NUMBER,
                ConfigDto.Configurations.TRADE_SELL_FEE_PCT.name.lowercase(Locale.getDefault()),
                "Percent (0-25, decimals allowed) skimmed off every Toby Coin SELL into the jackpot pool. Default 1.",
                false
            ),
            OptionData(
                OptionType.INTEGER,
                ConfigDto.Configurations.POKER_RAKE_PCT.name.lowercase(Locale.getDefault()),
                "Percent (0-20) of every settled poker pot routed into the per-guild jackpot pool. Default 5.",
                false
            ),
            OptionData(
                OptionType.INTEGER,
                ConfigDto.Configurations.POKER_SMALL_BLIND.name.lowercase(Locale.getDefault()),
                "Per-guild poker small blind for new tables. Default 5.",
                false
            ),
            OptionData(
                OptionType.INTEGER,
                ConfigDto.Configurations.POKER_BIG_BLIND.name.lowercase(Locale.getDefault()),
                "Per-guild poker big blind for new tables. Default 10.",
                false
            ),
            OptionData(
                OptionType.INTEGER,
                ConfigDto.Configurations.POKER_SMALL_BET.name.lowercase(Locale.getDefault()),
                "Per-guild fixed-limit small bet (pre-flop / flop). Default 10.",
                false
            ),
            OptionData(
                OptionType.INTEGER,
                ConfigDto.Configurations.POKER_BIG_BET.name.lowercase(Locale.getDefault()),
                "Per-guild fixed-limit big bet (turn / river). Default 20.",
                false
            ),
            OptionData(
                OptionType.INTEGER,
                ConfigDto.Configurations.POKER_MIN_BUY_IN.name.lowercase(Locale.getDefault()),
                "Per-guild minimum poker buy-in. Default 100.",
                false
            ),
            OptionData(
                OptionType.INTEGER,
                ConfigDto.Configurations.POKER_MAX_BUY_IN.name.lowercase(Locale.getDefault()),
                "Per-guild maximum poker buy-in. Default 5000.",
                false
            ),
            OptionData(
                OptionType.INTEGER,
                ConfigDto.Configurations.POKER_MAX_SEATS.name.lowercase(Locale.getDefault()),
                "Maximum players per poker table (2-9). Default 6.",
                false
            ),
            OptionData(
                OptionType.INTEGER,
                ConfigDto.Configurations.POKER_SHOT_CLOCK_SECONDS.name.lowercase(Locale.getDefault()),
                "Per-actor decision deadline in seconds (0 disables). Default 30.",
                false
            ),
            OptionData(
                OptionType.INTEGER,
                ConfigDto.Configurations.BLACKJACK_RAKE_PCT.name.lowercase(Locale.getDefault()),
                "Percent (0-20) of every settled blackjack pot routed into the per-guild jackpot pool. Default 5.",
                false
            ),
            OptionData(
                OptionType.INTEGER,
                ConfigDto.Configurations.BLACKJACK_MIN_ANTE.name.lowercase(Locale.getDefault()),
                "Per-guild minimum blackjack ante (also the solo stake floor). Default 10.",
                false
            ),
            OptionData(
                OptionType.INTEGER,
                ConfigDto.Configurations.BLACKJACK_MAX_ANTE.name.lowercase(Locale.getDefault()),
                "Per-guild maximum blackjack ante (also the solo stake ceiling). Default 500.",
                false
            ),
            OptionData(
                OptionType.INTEGER,
                ConfigDto.Configurations.BLACKJACK_MAX_SEATS.name.lowercase(Locale.getDefault()),
                "Maximum players per blackjack multi table (2-7). Default 5.",
                false
            ),
            // BLACKJACK_SHOT_CLOCK_SECONDS, BLACKJACK_DEALER_HITS_SOFT_17,
            // BLACKJACK_BJ_PAYOUT_NUM/DEN are intentionally NOT registered
            // as /setconfig options because Discord caps a single slash
            // command at 25 options total. They're still settable via the
            // /moderation web tab (ModerationWebService.updateConfig) and
            // their `when` validation in this file stays exhaustive.
            OptionData(
                OptionType.INTEGER,
                ConfigDto.Configurations.UBI_DAILY_AMOUNT.name.lowercase(Locale.getDefault()),
                "Credits granted to every known user once per UTC day, regardless of voice. 0 disables UBI.",
                false
            ),
            OptionData(
                OptionType.INTEGER,
                ConfigDto.Configurations.DAILY_CREDIT_CAP.name.lowercase(Locale.getDefault()),
                "Override for the daily social-credit cap that applies to voice/command/intro earnings. Default 90.",
                false
            )
        )
}
