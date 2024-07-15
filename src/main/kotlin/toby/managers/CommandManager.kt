package toby.managers

import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.internal.utils.tuple.Pair
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Configurable
import org.springframework.stereotype.Service
import toby.command.CommandContext
import toby.command.ICommand
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
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
import toby.helpers.DnDHelper
import toby.helpers.MusicPlayerHelper
import toby.helpers.UserDtoHelper
import toby.jpa.dto.ConfigDto
import toby.jpa.dto.UserDto
import toby.jpa.service.*
import toby.lavaplayer.PlayerManager
import java.util.*

@Service
@Configurable
class CommandManager @Autowired constructor(private val configService: IConfigService, private val brotherService: IBrotherService, private val userService: IUserService, private val musicFileService: IMusicFileService, private val excuseService: IExcuseService) {
    private val commands: MutableList<ICommand> = ArrayList()
    private val slashCommands: MutableList<CommandData?> = ArrayList()
    private val lastCommands: MutableMap<User, Pair<ICommand, CommandContext>> = HashMap()

    init {
        val cache: Cache = Cache(86400, 3600, 2)

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
        val nameFound = commands.stream().anyMatch { it.name.equals(cmd.name, true) }
        require(!nameFound) { "A command with this name is already present" }
        commands.add(cmd)
        val slashCommand = cmd.slashCommand
        slashCommand.addOptions(cmd.optionData)
        slashCommands.add(slashCommand)
    }

    val allSlashCommands: List<CommandData?> get() = slashCommands
    val allCommands: List<ICommand> get() = commands
    val musicCommands: List<ICommand> get() = commands.stream().filter { it is IMusicCommand }.toList()
    val moderationCommands: List<ICommand> get() = commands.stream().filter { it is IModerationCommand }.toList()
    val miscCommands: List<ICommand> get() = commands.stream().filter { it is IMiscCommand }.toList()
    val fetchCommands: List<ICommand> get() = commands.stream().filter { it is IFetchCommand }.toList()

    fun getCommand(search: String): ICommand? {
        for (cmd in commands) {
            if (cmd.name.equals(search, true)) {
                return cmd
            }
        }
        return null
    }

    fun handle(event: SlashCommandInteractionEvent) {
        val deleteDelay = configService.getConfigByName(ConfigDto.Configurations.DELETE_DELAY.configValue, event.guild!!.id)?.value?.toInt()
        val volumePropertyName = ConfigDto.Configurations.VOLUME.configValue
        val defaultVolume = configService.getConfigByName(volumePropertyName, event.guild!!.id)?.value
        val introVolume = defaultVolume?.toInt()
        val requestingUserDto = event.member?.let { UserDtoHelper.calculateUserDto(event.guild!!.idLong, event.user.idLong, it.isOwner, userService, introVolume!!) }
        val invoke = event.name.lowercase(Locale.getDefault())
        val cmd = getCommand(invoke)

        // Build the response embed
        if (cmd != null) {
            event.channel.sendTyping().queue()
            val ctx = CommandContext(event)
            lastCommands[event.user] = Pair.of(cmd, ctx)
            cmd.handle(ctx, requestingUserDto!!, deleteDelay)
            attributeSocialCredit(ctx, userService, requestingUserDto, deleteDelay!!)
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

    fun handle(event: ButtonInteractionEvent) {
        val deleteDelay = configService.getConfigByName(ConfigDto.Configurations.DELETE_DELAY.configValue, event.guild!!.id)?.value?.toInt()
        val volumePropertyName = ConfigDto.Configurations.VOLUME.configValue
        val defaultVolume = configService.getConfigByName(volumePropertyName, event.guild!!.id)?.value
        val introVolume = defaultVolume?.toInt()
        val guildId = event.guild!!.idLong
        val requestingUserDto = Objects.requireNonNull(event.member)?.let { UserDtoHelper.calculateUserDto(guildId, event.user.idLong, it.isOwner, userService, introVolume!!) }
        // Dispatch the simulated SlashCommandInteractionEvent
        val componentId = event.componentId
        if (componentId == "resend_last_request") {
            val iCommandCommandContextPair = lastCommands[event.user]!!
            val cmd = iCommandCommandContextPair.left
            // Resend the last request
            if (cmd != null) {
                event.channel.sendTyping().queue()
                cmd.handle(iCommandCommandContextPair.right, requestingUserDto!!, deleteDelay)
                event.deferEdit().queue()
            }
        } else {
            val hook = event.hook
            when (componentId) {
                "pause/play" -> MusicPlayerHelper.changePauseStatusOnTrack(event, PlayerManager.instance.getMusicManager(event.guild!!), deleteDelay!!)
                "stop" -> MusicPlayerHelper.stopSong(event, PlayerManager.instance.getMusicManager(event.guild!!), requestingUserDto!!.superUser, deleteDelay)
                "init:next" -> DnDHelper.incrementTurnTable(hook, event, deleteDelay)
                "init:prev" -> DnDHelper.decrementTurnTable(hook, event, deleteDelay)
                "init:clear" -> DnDHelper.clearInitiative(hook, event)
                else -> {

                    //button name that should be something like 'roll: 20,1,0'
                    val invoke = componentId.lowercase(Locale.getDefault())
                    val split = invoke.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                    val commandName = split[0]
                    val options = split[1]
                    val cmd = getCommand(commandName)
                    cmd?.let { nonNullCmd ->
                        event.channel.sendTyping().queue()
                        if (nonNullCmd.name.equals("roll")) {
                            val rollCommand = nonNullCmd as? RollCommand
                            val optionArray = options.split(",").map { it.trim() }.toTypedArray()
                            rollCommand?.handleDiceRoll(
                                event,
                                optionArray.getOrNull(0)?.toIntOrNull() ?: return@let,
                                optionArray.getOrNull(1)?.toIntOrNull() ?: return@let,
                                optionArray.getOrNull(2)?.toIntOrNull() ?: return@let
                            )?.queue { invokeDeleteOnMessageResponse(deleteDelay!!) }
                        }
                    }
                    val commandContext = CommandContext(event)
                    cmd!!.handle(commandContext, requestingUserDto!!, deleteDelay)
                }
            }
        }
    }
}
