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
                ConfigDto.Configurations.POKER_RAKE_PCT -> setPokerRake(event, optionMapping, deleteDelay)
                ConfigDto.Configurations.POKER_SMALL_BLIND -> setPokerLong(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.POKER_SMALL_BLIND, label = "small blind", min = 1L)
                ConfigDto.Configurations.POKER_BIG_BLIND -> setPokerLong(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.POKER_BIG_BLIND, label = "big blind", min = 1L)
                ConfigDto.Configurations.POKER_SMALL_BET -> setPokerLong(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.POKER_SMALL_BET, label = "small bet", min = 1L)
                ConfigDto.Configurations.POKER_BIG_BET -> setPokerLong(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.POKER_BIG_BET, label = "big bet", min = 1L)
                ConfigDto.Configurations.POKER_MIN_BUY_IN -> setPokerLong(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.POKER_MIN_BUY_IN, label = "minimum buy-in", min = 1L)
                ConfigDto.Configurations.POKER_MAX_BUY_IN -> setPokerLong(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.POKER_MAX_BUY_IN, label = "maximum buy-in", min = 1L)
                ConfigDto.Configurations.POKER_MAX_SEATS -> setPokerInt(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.POKER_MAX_SEATS, label = "max seats", range = 2..9)
                ConfigDto.Configurations.POKER_SHOT_CLOCK_SECONDS -> setPokerInt(event, optionMapping, deleteDelay,
                    config = ConfigDto.Configurations.POKER_SHOT_CLOCK_SECONDS,
                    label = "shot-clock seconds", range = 0..600)
            }
        }
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

    /**
     * Shared "validate a non-negative whole-number poker config value
     * within [[min], [Long.MAX_VALUE]] and persist it" helper. Each
     * specific table-shape config (blind / bet / buy-in) only differs
     * in the human-readable [label] and the floor; the message and
     * upsert plumbing is the same.
     */
    private fun setPokerLong(
        event: SlashCommandInteractionEvent,
        optionMapping: OptionMapping,
        deleteDelay: Int,
        config: ConfigDto.Configurations,
        label: String,
        min: Long
    ) {
        val value = optionMapping.asLong
        if (value < min) {
            event.hook.sendMessage("Poker $label must be at least $min.")
                .setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }
        val guildId = event.guild?.id ?: return
        configService.upsertConfig(config.configValue, value.toString(), guildId)
        event.hook.sendMessage("Poker $label set to $value chips for new tables.")
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    /**
     * As [setPokerLong] but for integer-bounded knobs (max seats,
     * shot-clock seconds). The range is inclusive on both ends.
     */
    private fun setPokerInt(
        event: SlashCommandInteractionEvent,
        optionMapping: OptionMapping,
        deleteDelay: Int,
        config: ConfigDto.Configurations,
        label: String,
        range: IntRange
    ) {
        val value = optionMapping.asInt
        if (value !in range) {
            event.hook.sendMessage("Poker $label must be between ${range.first} and ${range.last}.")
                .setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }
        val guildId = event.guild?.id ?: return
        configService.upsertConfig(config.configValue, value.toString(), guildId)
        event.hook.sendMessage("Poker $label set to $value for new tables.")
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
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
            )
        )
}
