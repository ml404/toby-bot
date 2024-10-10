package bot.toby.command.commands.moderation

import database.dto.ConfigDto
import database.dto.UserDto
import database.service.IConfigService
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import bot.toby.command.CommandContext
import bot.toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import java.util.*

class SetConfigCommand(private val configService: IConfigService) : IModerationCommand {

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx.event
        event.deferReply(true).queue()
        val member = ctx.member
        if (member?.isOwner != true) {
            event.hook.sendMessage("This is currently reserved for the owner of the server only, this may change in future")
                .setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))
            return
        }
        validateArgumentsAndUpdateConfigs(event, deleteDelay ?: 0)
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
            }
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
            )
        )
}
