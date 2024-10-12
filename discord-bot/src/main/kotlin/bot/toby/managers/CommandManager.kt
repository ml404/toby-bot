package bot.toby.managers

import bot.database.dto.ConfigDto
import bot.database.service.IConfigService
import bot.database.service.IExcuseService
import bot.database.service.IUserService
import bot.logging.DiscordLogger
import bot.toby.command.CommandContext
import bot.toby.command.ICommand
import bot.toby.command.commands.dnd.DnDCommand
import bot.toby.command.commands.dnd.IDnDCommand
import bot.toby.command.commands.dnd.InitiativeCommand
import bot.toby.command.commands.dnd.RollCommand
import bot.toby.command.commands.fetch.DbdRandomKillerCommand
import bot.toby.command.commands.fetch.IFetchCommand
import bot.toby.command.commands.fetch.Kf2RandomMapCommand
import bot.toby.command.commands.fetch.MemeCommand
import bot.toby.command.commands.misc.*
import bot.toby.command.commands.moderation.*
import bot.toby.command.commands.music.IMusicCommand
import bot.toby.command.commands.music.channel.JoinCommand
import bot.toby.command.commands.music.channel.LeaveCommand
import bot.toby.command.commands.music.intro.EditIntroCommand
import bot.toby.command.commands.music.intro.SetIntroCommand
import bot.toby.command.commands.music.player.*
import bot.toby.helpers.*
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Configurable
import java.util.*

@Configurable
class CommandManager @Autowired constructor(
    private val configService: IConfigService,
    brotherService: bot.database.service.IBrotherService,
    private val userService: IUserService,
    excuseService: IExcuseService,
    httpHelper: HttpHelper,
    private val userDtoHelper: UserDtoHelper,
    introHelper: IntroHelper,
    dndHelper: DnDHelper
) {
    private val commands: MutableList<ICommand> = ArrayList()
    private val slashCommands: MutableList<CommandData?> = ArrayList()
    val lastCommands: MutableMap<Guild, Pair<ICommand, CommandContext>> = HashMap()
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    init {
        val cache = Cache(86400, 3600, 2)

        //misc commands
        addCommand(HelpCommand(this))
        addCommand(RollCommand(dndHelper))
        addCommand(MemeCommand())
        addCommand(Kf2RandomMapCommand(cache))
        addCommand(DbdRandomKillerCommand(cache))
        addCommand(HelloThereCommand(configService))
        addCommand(BrotherCommand(brotherService))
        addCommand(ChCommand())
        addCommand(UserInfoCommand(userService))
        addCommand(RandomCommand())
        addCommand(TeamCommand())
        addCommand(ExcuseCommand(excuseService))
        addCommand(EightBallCommand(userService))

        //moderation commands
        addCommand(SetConfigCommand(configService))
        addCommand(KickCommand())
        addCommand(MoveCommand(configService))
        addCommand(ShhCommand())
        addCommand(TalkCommand())
        addCommand(PollCommand())
        addCommand(AdjustUserCommand(userService, userDtoHelper))
        addCommand(SocialCreditCommand(userService))

        //music commands
        addCommand(JoinCommand(configService))
        addCommand(LeaveCommand(configService))
        addCommand(PlayCommand())
        addCommand(NowDigOnThisCommand())
        addCommand(SetVolumeCommand())
        addCommand(PauseCommand())
        addCommand(ResumeCommand())
        addCommand(LoopCommand())
        addCommand(StopCommand())
        addCommand(SkipCommand())
        addCommand(NowPlayingCommand())
        addCommand(QueueCommand())
        addCommand(ShuffleCommand())
        addCommand(SetIntroCommand(introHelper))
        addCommand(EditIntroCommand())

        //dnd commands
        addCommand(InitiativeCommand(dndHelper))
        addCommand(DnDCommand(httpHelper = httpHelper, dndHelper = dndHelper))
    }

    private fun addCommand(cmd: ICommand) {
        val nameFound = commands.any { it.name.equals(cmd.name, true) }
        require(!nameFound) { "A command with this name is already present" }
        commands.add(cmd)
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
