package bot.toby.managers

import bot.database.dto.ConfigDto
import bot.database.service.IConfigService
import bot.database.service.IUserService
import bot.logging.DiscordLogger
import bot.toby.command.CommandContext
import bot.toby.command.ICommand
import bot.toby.command.commands.dnd.IDnDCommand
import bot.toby.command.commands.fetch.IFetchCommand
import bot.toby.command.commands.misc.IMiscCommand
import bot.toby.command.commands.moderation.IModerationCommand
import bot.toby.command.commands.music.IMusicCommand
import bot.toby.helpers.UserDtoHelper
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Configurable
import java.util.*

@Configurable
class CommandManager @Autowired constructor(
    private val configService: IConfigService,
    private val userService: IUserService,
    private val userDtoHelper: UserDtoHelper,
    private val commands: List<ICommand>
) {
    private val slashCommands: MutableList<CommandData?> = ArrayList()
    val lastCommands: MutableMap<Guild, Pair<ICommand, CommandContext>> = HashMap()
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    init {
        commands.forEach { addCommand(it) }
    }

    private fun addCommand(cmd: ICommand) {
        val nameFound = slashCommands.any { it?.name.equals(cmd.name, true) }
        require(!nameFound) { "A command with this name is already present" }
        val slashCommand = cmd.slashCommand
        slashCommand.addOptions(cmd.optionData)
        slashCommands.add(slashCommand)
    }

    val allSlashCommands: List<CommandData?> get() = slashCommands
    val allCommands: List<ICommand> get() = commands
    val musicCommands: List<ICommand> get() = commands.filterIsInstance<IMusicCommand>().toList()
    val dndCommands: List<ICommand> get() = commands.filterIsInstance<IDnDCommand>().toList()
    val moderationCommands: List<ICommand> get() = commands.filterIsInstance<IModerationCommand>().toList()
    val miscCommands: List<ICommand> get() = commands.filterIsInstance<IMiscCommand>().toList()
    val fetchCommands: List<ICommand> get() = commands.filterIsInstance<IFetchCommand>().toList()

    fun getCommand(search: String): ICommand? = commands.find { it.name.equals(search, true) }

    fun handle(event: SlashCommandInteractionEvent) {
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
        val cmd = getCommand(invoke)
        logger.info("Processing command '${cmd?.name}' ...")

        cmd?.let {
            event.channel.sendTyping().queue()
            val ctx = CommandContext(event)
            lastCommands[event.guild!!] = Pair(it, ctx)
            requestingUserDto?.let { userDto ->
                it.handle(ctx, userDto, deleteDelay)
                attributeSocialCredit(ctx, userService, userDto, deleteDelay)
            }
        }
    }

    private fun attributeSocialCredit(
        ctx: CommandContext,
        userService: IUserService,
        requestingUserDto: bot.database.dto.UserDto,
        deleteDelay: Int
    ) {
        val socialCreditScore = requestingUserDto.socialCredit
        val r = Random()
        val socialCredit = r.nextInt(5)
        val awardedSocialCredit = socialCredit * 5
        requestingUserDto.socialCredit = socialCreditScore?.plus(awardedSocialCredit)
        userService.updateUser(requestingUserDto)
        //        ctx.getEvent().getChannel().sendMessageFormat("Awarded '%s' with %d social credit", ctx.getAuthor().getName(), awardedSocialCredit).queue(invokeDeleteOnMessageResponse(deleteDelay));
    }
}
