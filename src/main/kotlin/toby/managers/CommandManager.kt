package toby.managers

import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Configurable
import org.springframework.stereotype.Service
import toby.command.CommandContext
import toby.command.ICommand
import toby.command.commands.dnd.DnDCommand
import toby.command.commands.dnd.InitiativeCommand
import toby.command.commands.fetch.DbdRandomKillerCommand
import toby.command.commands.fetch.IFetchCommand
import toby.command.commands.fetch.Kf2RandomMapCommand
import toby.command.commands.fetch.MemeCommand
import toby.command.commands.misc.*
import toby.command.commands.moderation.*
import toby.command.commands.music.*
import toby.helpers.Cache
import toby.helpers.UserDtoHelper
import toby.jpa.dto.ConfigDto
import toby.jpa.dto.UserDto
import toby.jpa.service.*
import java.util.*

@Service
@Configurable
class CommandManager @Autowired constructor(private val configService: IConfigService, private val brotherService: IBrotherService, private val userService: IUserService, private val musicFileService: IMusicFileService, private val excuseService: IExcuseService) {
    private val commands: MutableList<ICommand> = ArrayList()
    private val slashCommands: MutableList<CommandData?> = ArrayList()
    val lastCommands: MutableMap<User, Pair<ICommand, CommandContext>> = HashMap()

    init {
        val cache = Cache(86400, 3600, 2)

        //misc commands
        addCommand(HelpCommand(this))
        addCommand(RollCommand())
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
        addCommand(AdjustUserCommand(userService))
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
        addCommand(IntroSongCommand(userService, musicFileService, configService))

        //dnd commands
        addCommand(InitiativeCommand(userService))
        addCommand(DnDCommand())
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
    val moderationCommands: List<ICommand> get() = commands.filterIsInstance<IModerationCommand>().toList()
    val miscCommands: List<ICommand> get() = commands.filterIsInstance<IMiscCommand>().toList()
    val fetchCommands: List<ICommand> get() = commands.filterIsInstance<IFetchCommand>().toList()

    fun getCommand(search: String): ICommand? {
        for (cmd in commands) {
            if (cmd.name.equals(search, true)) {
                return cmd
            }
        }
        return null
    }

    fun handle(event: SlashCommandInteractionEvent) {
        val guildId = event.guild?.id ?: return
        val deleteDelay = configService.getConfigByName(ConfigDto.Configurations.DELETE_DELAY.configValue, guildId)?.value?.toIntOrNull() ?: 0
        val volumePropertyName = ConfigDto.Configurations.VOLUME.configValue
        val defaultVolume = configService.getConfigByName(volumePropertyName, guildId)?.value?.toIntOrNull()
        val requestingUserDto = event.member?.let { UserDtoHelper.calculateUserDto(guildId.toLong(), event.user.idLong, it.isOwner, userService, defaultVolume ?: 0) }
        val invoke = event.name.lowercase(Locale.getDefault())
        val cmd = getCommand(invoke)

        cmd?.let {
            event.channel.sendTyping().queue()
            val ctx = CommandContext(event)
            lastCommands[event.user] = Pair(it, ctx)
            requestingUserDto?.let { userDto ->
                it.handle(ctx, userDto, deleteDelay)
                attributeSocialCredit(ctx, userService, userDto, deleteDelay)
            }
        }
    }

    private fun attributeSocialCredit(ctx: CommandContext, userService: IUserService, requestingUserDto: UserDto, deleteDelay: Int) {
        val socialCreditScore = requestingUserDto.socialCredit
        val r = Random()
        val socialCredit = r.nextInt(5)
        val awardedSocialCredit = socialCredit * 5
        requestingUserDto.socialCredit = socialCreditScore?.plus(awardedSocialCredit)
        userService.updateUser(requestingUserDto)
        //        ctx.getEvent().getChannel().sendMessageFormat("Awarded '%s' with %d social credit", ctx.getAuthor().getName(), awardedSocialCredit).queue(invokeDeleteOnMessageResponse(deleteDelay));
    }
}
