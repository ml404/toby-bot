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
        val newConfigDto = ConfigDto(configValue, channel.id, guildId)
        val databaseConfig = configService.getConfigByName(configValue, guildId)
        if (databaseConfig != null && databaseConfig.guildId == newConfigDto.guildId) {
            configService.updateConfig(newConfigDto)
        } else {
            configService.createNewConfig(newConfigDto)
        }
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

        val newConfigDto = ConfigDto(configValue, enabled.toString(), guildId)
        val existing = configService.getConfigByName(configValue, guildId)
        val previouslyEnabled = existing?.value?.equals("true", ignoreCase = true) == true

        if (existing != null && existing.guildId == newConfigDto.guildId) {
            configService.updateConfig(newConfigDto)
        } else {
            configService.createNewConfig(newConfigDto)
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

        val newConfigDto = ConfigDto(configValue, newValue.toString(), guildId)
        val databaseConfig = configService.getConfigByName(configValue, guildId)

        if (databaseConfig != null && databaseConfig.guildId == newConfigDto.guildId) {
            configService.updateConfig(newConfigDto)
        } else {
            configService.createNewConfig(newConfigDto)
        }
        event.hook.sendMessage(messageToSend).queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun setMove(event: SlashCommandInteractionEvent, deleteDelay: Int) {
        val movePropertyName = ConfigDto.Configurations.MOVE.configValue
        val guildId = event.guild?.id ?: return
        val newDefaultMoveChannel =
            event.getOption(ConfigDto.Configurations.MOVE.name.lowercase(Locale.getDefault()))?.asChannel

        if (newDefaultMoveChannel != null) {
            val newConfigDto = ConfigDto(movePropertyName, newDefaultMoveChannel.name, guildId)
            val databaseConfig = configService.getConfigByName(movePropertyName, guildId)

            if (databaseConfig != null && databaseConfig.guildId == newConfigDto.guildId) {
                configService.updateConfig(newConfigDto)
            } else {
                configService.createNewConfig(newConfigDto)
            }
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
            )
        )
}
