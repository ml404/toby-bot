package bot.toby.command.commands.moderation

import bot.toby.activity.ActivityTrackingNotifier
import core.command.Command.Companion.replyAndDelete
import core.command.Command.Companion.replyEphemeralAndDelete
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
            event.hook.replyEphemeralAndDelete(
                "This is currently reserved for the owner of the server only, this may change in future",
                deleteDelay
            )
            return
        }
        validateArgumentsAndUpdateConfigs(event, deleteDelay)
    }

    private fun validateArgumentsAndUpdateConfigs(event: SlashCommandInteractionEvent, deleteDelay: Int) {
        val options = event.options
        if (options.isEmpty()) {
            event.hook.replyAndDelete(description, deleteDelay)
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
                // Per-game stake bounds + jackpot scaling anchor. Not
                // registered as /setconfig OptionData because Discord caps
                // the command at 25 options and these are better grouped
                // in the /moderation web tab. Dispatch branches are kept
                // exhaustive in case the option is registered manually
                // (e.g. by direct API invocation).
                ConfigDto.Configurations.DICE_MIN_STAKE -> setMinimumLongConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.DICE_MIN_STAKE, gameLabel = "Dice", label = "minimum stake", min = 1L, unit = "credits")
                ConfigDto.Configurations.DICE_MAX_STAKE -> setMinimumLongConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.DICE_MAX_STAKE, gameLabel = "Dice", label = "maximum stake", min = 1L, unit = "credits")
                ConfigDto.Configurations.COINFLIP_MIN_STAKE -> setMinimumLongConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.COINFLIP_MIN_STAKE, gameLabel = "Coinflip", label = "minimum stake", min = 1L, unit = "credits")
                ConfigDto.Configurations.COINFLIP_MAX_STAKE -> setMinimumLongConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.COINFLIP_MAX_STAKE, gameLabel = "Coinflip", label = "maximum stake", min = 1L, unit = "credits")
                ConfigDto.Configurations.SLOTS_MIN_STAKE -> setMinimumLongConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.SLOTS_MIN_STAKE, gameLabel = "Slots", label = "minimum stake", min = 1L, unit = "credits")
                ConfigDto.Configurations.SLOTS_MAX_STAKE -> setMinimumLongConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.SLOTS_MAX_STAKE, gameLabel = "Slots", label = "maximum stake", min = 1L, unit = "credits")
                ConfigDto.Configurations.HIGHLOW_MIN_STAKE -> setMinimumLongConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.HIGHLOW_MIN_STAKE, gameLabel = "Highlow", label = "minimum stake", min = 1L, unit = "credits")
                ConfigDto.Configurations.HIGHLOW_MAX_STAKE -> setMinimumLongConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.HIGHLOW_MAX_STAKE, gameLabel = "Highlow", label = "maximum stake", min = 1L, unit = "credits")
                ConfigDto.Configurations.BACCARAT_MIN_STAKE -> setMinimumLongConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.BACCARAT_MIN_STAKE, gameLabel = "Baccarat", label = "minimum stake", min = 1L, unit = "credits")
                ConfigDto.Configurations.BACCARAT_MAX_STAKE -> setMinimumLongConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.BACCARAT_MAX_STAKE, gameLabel = "Baccarat", label = "maximum stake", min = 1L, unit = "credits")
                ConfigDto.Configurations.KENO_MIN_STAKE -> setMinimumLongConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.KENO_MIN_STAKE, gameLabel = "Keno", label = "minimum stake", min = 1L, unit = "credits")
                ConfigDto.Configurations.KENO_MAX_STAKE -> setMinimumLongConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.KENO_MAX_STAKE, gameLabel = "Keno", label = "maximum stake", min = 1L, unit = "credits")
                ConfigDto.Configurations.SCRATCH_MIN_STAKE -> setMinimumLongConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.SCRATCH_MIN_STAKE, gameLabel = "Scratch", label = "minimum stake", min = 1L, unit = "credits")
                ConfigDto.Configurations.SCRATCH_MAX_STAKE -> setMinimumLongConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.SCRATCH_MAX_STAKE, gameLabel = "Scratch", label = "maximum stake", min = 1L, unit = "credits")
                ConfigDto.Configurations.ROULETTE_MIN_STAKE -> setMinimumLongConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.ROULETTE_MIN_STAKE, gameLabel = "Roulette", label = "minimum stake", min = 1L, unit = "credits")
                ConfigDto.Configurations.ROULETTE_MAX_STAKE -> setMinimumLongConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.ROULETTE_MAX_STAKE, gameLabel = "Roulette", label = "maximum stake", min = 1L, unit = "credits")
                ConfigDto.Configurations.HOLDEM_MIN_STAKE -> setMinimumLongConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.HOLDEM_MIN_STAKE, gameLabel = "Casino Hold'em", label = "minimum stake", min = 1L, unit = "credits")
                ConfigDto.Configurations.HOLDEM_MAX_STAKE -> setMinimumLongConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.HOLDEM_MAX_STAKE, gameLabel = "Casino Hold'em", label = "maximum stake", min = 1L, unit = "credits")
                ConfigDto.Configurations.DUEL_MIN_STAKE -> setMinimumLongConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.DUEL_MIN_STAKE, gameLabel = "Duel", label = "minimum stake", min = 1L, unit = "credits")
                ConfigDto.Configurations.DUEL_MAX_STAKE -> setMinimumLongConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.DUEL_MAX_STAKE, gameLabel = "Duel", label = "maximum stake", min = 1L, unit = "credits")
                ConfigDto.Configurations.JACKPOT_STAKE_ANCHOR -> setMinimumLongConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.JACKPOT_STAKE_ANCHOR, gameLabel = "Jackpot", label = "stake anchor", min = 1L, unit = "credits")
                // Post-fraud rebalance gates. Like the per-game stake
                // bounds above, these aren't in the /setconfig OptionData
                // (admins use the /moderation web tab) but keep the
                // dispatch exhaustive in case the option is registered
                // manually.
                ConfigDto.Configurations.JACKPOT_WHEEL_SEGMENTS -> {
                    // No simple int range — admins edit the wheel via
                    // the /moderation web tab. Surface a friendly error
                    // if someone tries to set it via /setconfig so the
                    // dispatch stays exhaustive but doesn't pretend to
                    // validate the CSV here.
                    event.hook.replyAndDelete(
                        "Edit the payout wheel via the moderation web tab — the CSV format needs the dedicated editor.",
                        deleteDelay,
                    )
                }
                ConfigDto.Configurations.JACKPOT_WINNER_COOLDOWN_DAYS -> setRangedIntConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.JACKPOT_WINNER_COOLDOWN_DAYS, gameLabel = "Jackpot", label = "winner cooldown days", range = 0..365)
                ConfigDto.Configurations.JACKPOT_ACTIVITY_WINDOW_DAYS -> setRangedIntConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.JACKPOT_ACTIVITY_WINDOW_DAYS, gameLabel = "Jackpot", label = "activity window days", range = 0..365)
                ConfigDto.Configurations.JACKPOT_ACTIVITY_MIN_DAYS -> setRangedIntConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.JACKPOT_ACTIVITY_MIN_DAYS, gameLabel = "Jackpot", label = "activity min days", range = 1..365)
                ConfigDto.Configurations.JACKPOT_RTP_MAX_PCT -> setRangedIntConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.JACKPOT_RTP_MAX_PCT, gameLabel = "Jackpot", label = "max RTP percent (0 disables)", range = 0..100)
                ConfigDto.Configurations.COINFLIP_BOT_EDGE_MAX_PCT -> setRangedIntConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.COINFLIP_BOT_EDGE_MAX_PCT, gameLabel = "Coinflip",
                    label = "bot-suspicion max edge percent", range = 0..50)
                ConfigDto.Configurations.DICE_BOT_EDGE_MAX_PCT -> setRangedIntConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.DICE_BOT_EDGE_MAX_PCT, gameLabel = "Dice",
                    label = "bot-suspicion max edge percent", range = 0..50)
                ConfigDto.Configurations.SLOTS_BOT_EDGE_MAX_PCT -> setRangedIntConfig(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.SLOTS_BOT_EDGE_MAX_PCT, gameLabel = "Slots",
                    label = "bot-suspicion max edge percent", range = 0..50)
                // Daily match-numbers lottery — moderation web tab is the
                // primary surface (toggles + daily-prize parameters), but
                // keep the dispatch arms exhaustive for direct API use.
                ConfigDto.Configurations.LOTTERY_DAILY_ENABLED -> setBooleanConfig(
                    event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.LOTTERY_DAILY_ENABLED,
                    label = "daily lottery"
                )
                ConfigDto.Configurations.LOTTERY_DAILY_TICKET_PRICE -> setMinimumLongConfig(
                    event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.LOTTERY_DAILY_TICKET_PRICE,
                    gameLabel = "Daily lottery", label = "ticket price", min = 1L, unit = "credits"
                )
                ConfigDto.Configurations.LOTTERY_DAILY_SEED_PCT -> setRangedIntConfig(
                    event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.LOTTERY_DAILY_SEED_PCT,
                    gameLabel = "Daily lottery", label = "jackpot seed percent", range = 1..100
                )
                ConfigDto.Configurations.LOTTERY_DAILY_REVENUE_JACKPOT_PCT -> setRangedIntConfig(
                    event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.LOTTERY_DAILY_REVENUE_JACKPOT_PCT,
                    gameLabel = "Daily lottery", label = "ticket revenue → jackpot percent", range = 0..100
                )
                ConfigDto.Configurations.LOTTERY_DAILY_MODE -> setLotteryDailyMode(
                    event, optionMapping, deleteDelay
                )
                ConfigDto.Configurations.LOTTERY_DAILY_MIN_BUYERS -> setRangedIntConfig(
                    event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.LOTTERY_DAILY_MIN_BUYERS,
                    gameLabel = "Daily lottery", label = "minimum distinct buyers", range = 1..50
                )
                ConfigDto.Configurations.LOTTERY_CHANNEL -> setLotteryChannel(
                    event, optionMapping, deleteDelay
                )
                ConfigDto.Configurations.LOTTERY_PING_MODE -> setLotteryPingMode(
                    event, optionMapping, deleteDelay
                )
                // Anti-autoclicker session log channel — managed primarily
                // via the /moderation web tab; arm stays exhaustive in case
                // the option is registered manually.
                ConfigDto.Configurations.CASINO_MODLOG_CHANNEL_ID -> setCasinoModlogChannel(
                    event, optionMapping, deleteDelay
                )
            }
        }
    }

    /**
     * Validate + persist the per-guild text-channel id for anti-autoclick
     * session embeds. Empty / `0` clears the override (notifier falls back
     * to the guild's system channel). Otherwise the value must parse to a
     * Long that resolves to a real text channel in this guild.
     */
    private fun setCasinoModlogChannel(
        event: SlashCommandInteractionEvent,
        optionMapping: OptionMapping,
        deleteDelay: Int,
    ) {
        val raw = optionMapping.asString.trim()
        val guild = event.guild ?: return
        val resolved: String = if (raw.isEmpty() || raw == "0") {
            ""
        } else {
            val id = raw.toLongOrNull() ?: run {
                event.hook.replyEphemeralAndDelete(
                    "Channel id must be numeric (or empty / 0 to clear).",
                    deleteDelay,
                )
                return
            }
            guild.getTextChannelById(id) ?: run {
                event.hook.replyEphemeralAndDelete(
                    "No text channel with id $id exists in this server.",
                    deleteDelay,
                )
                return
            }
            id.toString()
        }
        configService.upsertConfig(
            ConfigDto.Configurations.CASINO_MODLOG_CHANNEL_ID.configValue, resolved, guild.id
        )
        val msg = if (resolved.isEmpty()) {
            "Anti-autoclick session log channel cleared. Falling back to the guild's system channel."
        } else {
            "Anti-autoclick session events will post to <#$resolved>."
        }
        event.hook.replyAndDelete(msg, deleteDelay)
    }

    /**
     * Validate + persist the daily-lottery announce channel id. Accepts
     * a numeric channel id (or 0 / empty to clear and fall back through
     * LEADERBOARD_CHANNEL → systemChannel). Rejects ids that don't
     * resolve to a text channel in the current guild — admins can't
     * usefully target someone else's channel.
     */
    private fun setLotteryChannel(
        event: SlashCommandInteractionEvent,
        optionMapping: OptionMapping,
        deleteDelay: Int,
    ) {
        val raw = optionMapping.asString.trim()
        val guild = event.guild ?: return
        val resolved: String = if (raw.isEmpty() || raw == "0") {
            ""
        } else {
            val id = raw.toLongOrNull() ?: run {
                event.hook.replyEphemeralAndDelete(
                    "Channel id must be numeric (or empty / 0 to clear).",
                    deleteDelay,
                )
                return
            }
            guild.getTextChannelById(id) ?: run {
                event.hook.replyEphemeralAndDelete(
                    "No text channel with id $id exists in this server.",
                    deleteDelay,
                )
                return
            }
            id.toString()
        }
        configService.upsertConfig(
            ConfigDto.Configurations.LOTTERY_CHANNEL.configValue, resolved, guild.id
        )
        val msg = if (resolved.isEmpty()) {
            "Daily lottery announce channel cleared. Falling back to LEADERBOARD_CHANNEL → system channel."
        } else {
            "Daily lottery announcements will post to <#$resolved>."
        }
        event.hook.replyAndDelete(msg, deleteDelay)
    }

    /**
     * Validate + persist the daily-lottery mode (NUMBER_MATCH | WEIGHTED).
     * Stored uppercase so [LotteryHelper.dailyMode] reads cleanly.
     */
    private fun setLotteryDailyMode(
        event: SlashCommandInteractionEvent,
        optionMapping: OptionMapping,
        deleteDelay: Int,
    ) {
        val raw = optionMapping.asString.trim().uppercase()
        if (raw !in setOf("NUMBER_MATCH", "WEIGHTED")) {
            event.hook.replyEphemeralAndDelete(
                "Daily lottery mode must be NUMBER_MATCH or WEIGHTED.",
                deleteDelay,
            )
            return
        }
        val guildId = event.guild?.id ?: return
        configService.upsertConfig(
            ConfigDto.Configurations.LOTTERY_DAILY_MODE.configValue, raw, guildId
        )
        event.hook.replyAndDelete("Daily lottery mode set to $raw.", deleteDelay)
    }

    private fun setLotteryPingMode(
        event: SlashCommandInteractionEvent,
        optionMapping: OptionMapping,
        deleteDelay: Int,
    ) {
        val raw = optionMapping.asString.trim().uppercase()
        if (raw !in setOf("OFF", "HERE", "EVERYONE")) {
            event.hook.replyEphemeralAndDelete(
                "Lottery ping mode must be OFF, HERE, or EVERYONE.",
                deleteDelay,
            )
            return
        }
        val guildId = event.guild?.id ?: return
        configService.upsertConfig(
            ConfigDto.Configurations.LOTTERY_PING_MODE.configValue, raw, guildId
        )
        event.hook.replyAndDelete("Lottery announcement ping set to $raw.", deleteDelay)
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
            event.hook.replyEphemeralAndDelete(
                "$gameLabel $label must be between ${range.first} and ${range.last}.",
                deleteDelay,
            )
            return
        }
        val guildId = event.guild?.id ?: return
        configService.upsertConfig(config.configValue, value.toString(), guildId)
        event.hook.replyAndDelete("$gameLabel $label set to $value for new tables.", deleteDelay)
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
            event.hook.replyEphemeralAndDelete(
                "$gameLabel $label must be at least $min.",
                deleteDelay,
            )
            return
        }
        val guildId = event.guild?.id ?: return
        configService.upsertConfig(config.configValue, value.toString(), guildId)
        event.hook.replyAndDelete("$gameLabel $label set to $value $unit for new tables.", deleteDelay)
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
        event.hook.replyAndDelete("Blackjack dealer will now $rule.", deleteDelay)
    }

    /**
     * Persist a boolean config knob ("true"/"false") and reply with a
     * uniform "$label is now enabled/disabled." message. Used by simple
     * on/off toggles like the daily lottery.
     */
    private fun setBooleanConfig(
        event: SlashCommandInteractionEvent,
        optionMapping: OptionMapping,
        deleteDelay: Int,
        config: ConfigDto.Configurations,
        label: String,
    ) {
        val value = optionMapping.asBoolean
        val guildId = event.guild?.id ?: return
        configService.upsertConfig(config.configValue, value.toString(), guildId)
        val state = if (value) "enabled" else "disabled"
        event.hook.replyAndDelete("$label is now $state.", deleteDelay)
    }

    private fun setLeaderboardChannel(event: SlashCommandInteractionEvent, deleteDelay: Int) {
        val configValue = ConfigDto.Configurations.LEADERBOARD_CHANNEL.configValue
        val guildId = event.guild?.id ?: return
        val channel = event.getOption(
            ConfigDto.Configurations.LEADERBOARD_CHANNEL.name.lowercase(Locale.getDefault())
        )?.asChannel
        if (channel == null) {
            event.hook.replyEphemeralAndDelete(
                "No valid text channel was mentioned, so config was not updated",
                deleteDelay,
            )
            return
        }
        configService.upsertConfig(configValue, channel.id, guildId)
        event.hook.replyEphemeralAndDelete(
            "Monthly leaderboards will now post in <#${channel.id}> on the 1st of each month.",
            deleteDelay,
        )
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
        event.hook.replyEphemeralAndDelete(message, deleteDelay)

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
            event.hook.replyEphemeralAndDelete(
                "Value given invalid (a whole number representing percent)",
                deleteDelay,
            )
            return
        }

        val configValue =
            ConfigDto.Configurations.valueOf(optionMapping.name.uppercase(Locale.getDefault())).configValue
        val guildId = event.guild?.id ?: return

        configService.upsertConfig(configValue, newValue.toString(), guildId)
        event.hook.replyAndDelete(messageToSend, deleteDelay)
    }

    private fun setJackpotLossTribute(
        event: SlashCommandInteractionEvent,
        optionMapping: OptionMapping,
        deleteDelay: Int
    ) {
        val pct = optionMapping.asInt
        if (pct !in 0..50) {
            event.hook.replyEphemeralAndDelete(
                "Tribute percent must be between 0 and 50 (default 10).",
                deleteDelay,
            )
            return
        }
        val configValue = ConfigDto.Configurations.JACKPOT_LOSS_TRIBUTE_PCT.configValue
        val guildId = event.guild?.id ?: return
        configService.upsertConfig(configValue, pct.toString(), guildId)
        event.hook.replyAndDelete("Jackpot loss-tribute set to $pct % of every lost casino stake.", deleteDelay)
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
            event.hook.replyEphemeralAndDelete(
                "Trade $label fee percent must be between 0 and 25 (default 1).",
                deleteDelay,
            )
            return
        }
        val guildId = event.guild?.id ?: return
        configService.upsertConfig(config.configValue, pct.toString(), guildId)
        event.hook.replyAndDelete(
            "Toby Coin $label fee set to $pct % per leg (routed into the jackpot pool).",
            deleteDelay,
        )
    }

    private fun setJackpotWinPct(
        event: SlashCommandInteractionEvent,
        optionMapping: OptionMapping,
        deleteDelay: Int
    ) {
        val pct = optionMapping.asDouble
        if (pct.isNaN() || pct.isInfinite() || pct < 0.0 || pct > 50.0) {
            event.hook.replyEphemeralAndDelete(
                "Jackpot win percent must be between 0 and 50 (default 1).",
                deleteDelay,
            )
            return
        }
        val configValue = ConfigDto.Configurations.JACKPOT_WIN_PCT.configValue
        val guildId = event.guild?.id ?: return
        configService.upsertConfig(configValue, pct.toString(), guildId)
        event.hook.replyAndDelete("Jackpot win chance set to $pct % per casino-game win.", deleteDelay)
    }

    private fun setPokerRake(
        event: SlashCommandInteractionEvent,
        optionMapping: OptionMapping,
        deleteDelay: Int
    ) {
        val pct = optionMapping.asInt
        if (pct !in 0..20) {
            event.hook.replyEphemeralAndDelete(
                "Poker rake must be between 0 and 20 (default 5).",
                deleteDelay,
            )
            return
        }
        val configValue = ConfigDto.Configurations.POKER_RAKE_PCT.configValue
        val guildId = event.guild?.id ?: return
        configService.upsertConfig(configValue, pct.toString(), guildId)
        event.hook.replyAndDelete("Poker rake set to $pct % of every settled pot.", deleteDelay)
    }

    private fun setUbiDailyAmount(
        event: SlashCommandInteractionEvent,
        optionMapping: OptionMapping,
        deleteDelay: Int
    ) {
        val amount = optionMapping.asInt
        if (amount !in 0..1000) {
            event.hook.replyEphemeralAndDelete(
                "UBI daily amount must be between 0 and 1000 (0 disables UBI).",
                deleteDelay,
            )
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
        event.hook.replyAndDelete(message, deleteDelay)
    }

    private fun setDailyCreditCap(
        event: SlashCommandInteractionEvent,
        optionMapping: OptionMapping,
        deleteDelay: Int
    ) {
        val cap = optionMapping.asInt
        if (cap !in 0..10000) {
            event.hook.replyEphemeralAndDelete(
                "Daily credit cap must be between 0 and 10000 (default 90).",
                deleteDelay,
            )
            return
        }
        val configValue = ConfigDto.Configurations.DAILY_CREDIT_CAP.configValue
        val guildId = event.guild?.id ?: return
        configService.upsertConfig(configValue, cap.toString(), guildId)
        event.hook.replyAndDelete(
            "Daily social-credit cap set to $cap credits (covers voice, command, intro, and UI-trade earnings).",
            deleteDelay,
        )
    }

    private fun setMove(event: SlashCommandInteractionEvent, deleteDelay: Int) {
        val movePropertyName = ConfigDto.Configurations.MOVE.configValue
        val guildId = event.guild?.id ?: return
        val newDefaultMoveChannel =
            event.getOption(ConfigDto.Configurations.MOVE.name.lowercase(Locale.getDefault()))?.asChannel

        if (newDefaultMoveChannel != null) {
            configService.upsertConfig(movePropertyName, newDefaultMoveChannel.name, guildId)
            event.hook.replyEphemeralAndDelete(
                "Set default move channel to '${newDefaultMoveChannel.name}'",
                deleteDelay,
            )
        } else {
            event.hook.replyEphemeralAndDelete(
                "No valid channel was mentioned, so config was not updated",
                deleteDelay,
            )
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
                "Per-guild minimum blackjack stake (applies to both solo hands and multi-table antes). Default 10.",
                false
            ),
            OptionData(
                OptionType.INTEGER,
                ConfigDto.Configurations.BLACKJACK_MAX_ANTE.name.lowercase(Locale.getDefault()),
                "Per-guild maximum blackjack stake (applies to both solo hands and multi-table antes). Default 500.",
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
