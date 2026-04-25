package bot.toby.managers

import bot.toby.command.DefaultCommandContext
import bot.toby.command.commands.dnd.DnDCommand
import bot.toby.command.commands.economy.EconomyCommand
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
import database.service.ConfigService
import database.service.SocialCreditAwardService
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Configurable
import java.util.*

@Configurable
class DefaultCommandManager @Autowired constructor(
    private val configService: ConfigService,
    private val userDtoHelper: UserDtoHelper,
    private val awardService: SocialCreditAwardService,
    override val commands: List<Command>
) : CommandManager {
    override val slashCommands: MutableList<CommandData?> = ArrayList()
    override val lastCommands: MutableMap<Guild, Pair<Command, CommandContext>> = HashMap()
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    init {
        commands.forEach { addCommand(it) }
    }

    private fun addCommand(cmd: Command) {
        // Accessing the 'name' from the Command instance
        val nameFound = slashCommands.any { it?.name.equals(cmd.name, true) }
        require(!nameFound) { "A command with this name is already present" }

        // Accessing 'slashCommand' and 'optionData' from the Command instance
        val slashCommand = cmd.slashCommand

        when {
            cmd.subCommands.isNotEmpty() -> {
                slashCommand.addSubcommands(cmd.subCommands)
            }
            cmd.optionData.isNotEmpty() -> {
                slashCommand.addOptions(cmd.optionData)
            }
        }

        slashCommands.add(slashCommand)
    }

    val allSlashCommands: List<CommandData?> get() = slashCommands
    val allCommands: List<Command> get() = commands
    override val musicCommands: List<Command> get() = commands.filterIsInstance<MusicCommand>()
    override val dndCommands: List<Command> get() = commands.filterIsInstance<DnDCommand>()
    override val moderationCommands: List<Command> get() = commands.filterIsInstance<ModerationCommand>()
    override val miscCommands: List<Command> get() = commands.filterIsInstance<MiscCommand>()
    override val fetchCommands: List<Command> get() = commands.filterIsInstance<FetchCommand>()
    override val economyCommands: List<Command> get() = commands.filterIsInstance<EconomyCommand>()

    override fun handle(event: SlashCommandInteractionEvent) {
        val guildId = event.guild?.id ?: return
        val deleteDelay = configService.getConfigByName(
            ConfigDto.Configurations.DELETE_DELAY.configValue,
            guildId
        )?.value?.toIntOrNull() ?: 5

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
            val ctx = DefaultCommandContext(event)
            lastCommands[event.guild!!] = Pair(it, ctx)
            requestingUserDto?.let { userDto ->
                // Award before dispatch so a throwing command still earns credit
                // and so all user-initiated actions share the same hook point.
                awardCommandCredit(userDto, invoke)
                it.handle(ctx, userDto, deleteDelay)
            }
        }
    }

    private fun awardCommandCredit(userDto: UserDto, invoke: String) {
        val amount = Random().nextInt(5) * 5L
        awardService.award(
            discordId = userDto.discordId,
            guildId = userDto.guildId,
            amount = amount,
            reason = "command:$invoke"
        )
    }
}
