package bot.toby.managers

import bot.toby.command.CommandContextImpl
import bot.toby.command.commands.dnd.DnDSearchCommand
import bot.toby.command.commands.fetch.FetchCommand
import bot.toby.command.commands.misc.MiscCommand
import bot.toby.command.commands.moderation.ModerationCommand
import bot.toby.command.commands.music.MusicCommand
import bot.toby.helpers.UserDtoHelper
import common.logging.DiscordLogger
import core.command.Command
import core.command.CommandContext
import core.managers.CommandManager
import database.dto.ConfigDto
import database.dto.UserDto
import database.service.IConfigService
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Configurable
import java.util.*

@Configurable
class CommandManagerImpl @Autowired constructor(
    private val configService: IConfigService,
    private val userDtoHelper: UserDtoHelper,
    override val commands: List<Command>
) : CommandManager {
    override val slashCommands: MutableList<CommandData?> = ArrayList()
    val lastCommands: MutableMap<Guild, Pair<Command, CommandContext>> = HashMap()
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    init {
        commands.forEach { addCommand(it) }
    }

    private fun addCommand(cmd: Command) {
        // Accessing the 'name' from the ICommand instance
        val nameFound = slashCommands.any { it?.name.equals(cmd.name, true) }
        require(!nameFound) { "A command with this name is already present" }

        // Accessing 'slashCommand' and 'optionData' from the ICommand instance
        val slashCommand = cmd.slashCommand
        slashCommand.addOptions(cmd.optionData)
        slashCommands.add(slashCommand)
    }

    val allSlashCommands: List<CommandData?> get() = slashCommands
    val allCommands: List<Command> get() = commands
    override val musicCommands: List<Command> get() = commands.filterIsInstance<MusicCommand>().toList()
    override val dndCommands: List<Command> get() = commands.filterIsInstance<DnDSearchCommand>().toList()
    override val moderationCommands: List<Command> get() = commands.filterIsInstance<ModerationCommand>().toList()
    override val miscCommands: List<Command> get() = commands.filterIsInstance<MiscCommand>().toList()
    override val fetchCommands: List<Command> get() = commands.filterIsInstance<FetchCommand>().toList()

    override fun handle(event: SlashCommandInteractionEvent) {
        val guildId = event.guild?.id ?: return
        val deleteDelay = configService.getConfigByName(
            ConfigDto.Configurations.DELETE_DELAY.configValue,
            guildId
        )?.value?.toIntOrNull() ?: 0

        val requestingUserDto = event.member?.let {
            userDtoHelper.calculateUserDto(
                event.user.idLong,
                event.guild?.idLong!!,
                it.isOwner,
            )
        }

        logger.setGuildAndMemberContext(event.guild, event.member)
        val invoke = event.name.lowercase(Locale.getDefault())

        // Get the command by name
        val cmd = getCommand(invoke)
        logger.info("Processing command '${cmd?.name}' ...")

        cmd?.let {
            event.channel.sendTyping().queue()
            val ctx = CommandContextImpl(event)
            lastCommands[event.guild!!] = Pair(it, ctx)
            requestingUserDto?.let { userDto ->
                it.handle(ctx, userDto, deleteDelay)
                attributeSocialCredit(ctx, userDtoHelper, userDto, deleteDelay)
            }
        }
    }

    private fun attributeSocialCredit(
        ctx: CommandContext,
        userDtoHelper: UserDtoHelper,
        requestingUserDto: UserDto,
        deleteDelay: Int
    ) {
        val socialCreditScore = requestingUserDto.socialCredit
        val r = Random()
        val socialCredit = r.nextInt(5)
        val awardedSocialCredit = socialCredit * 5
        requestingUserDto.socialCredit = socialCreditScore?.plus(awardedSocialCredit)
        userDtoHelper.updateUser(requestingUserDto)
    }
}
