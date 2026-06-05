package bot.toby.managers

import bot.toby.command.DefaultCommandContext
import bot.toby.command.commands.dnd.DnDCommand
import bot.toby.command.commands.economy.EconomyCommand
import bot.toby.command.commands.fetch.FetchCommand
import bot.toby.command.commands.game.pvp.GameCommand
import bot.toby.command.commands.misc.MiscCommand
import bot.toby.command.commands.moderation.ModerationCommand
import bot.toby.command.commands.mtg.MtgCommand
import bot.toby.command.commands.music.MusicCommand
import bot.toby.helpers.UserDtoHelper
import common.logging.DiscordLogger
import core.command.Command
import core.managers.CommandManager
import database.dto.guild.ConfigDto
import database.dto.user.UserDto
import database.service.guild.ConfigService
import database.service.social.SocialCreditAwardService
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
    override val gameCommands: List<Command> get() = commands.filterIsInstance<GameCommand>()
    override val mtgCommands: List<Command> get() = commands.filterIsInstance<MtgCommand>()

    override fun handle(event: SlashCommandInteractionEvent) {
        val guildId = event.guild?.id ?: return
        val invoke = event.name.lowercase(Locale.getDefault())
        val cmd = getCommand(invoke)

        // Defer first — the pre-dispatch DB lookups below can otherwise
        // eat the 3-second Discord ack window when the DB is slow.
        cmd?.takeIf { it.defersReply }?.let {
            event.deferReply(it.ephemeral).queue()
        }

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
        logger.info("Processing command '${cmd?.name}' ...")

        cmd?.let {
            event.channel.sendTyping().queue()
            val ctx = DefaultCommandContext(event)
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
