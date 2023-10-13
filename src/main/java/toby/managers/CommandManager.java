package toby.managers;

import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.internal.utils.tuple.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.stereotype.Service;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.command.commands.fetch.DbdRandomKillerCommand;
import toby.command.commands.fetch.IFetchCommand;
import toby.command.commands.fetch.Kf2RandomMapCommand;
import toby.command.commands.fetch.MemeCommand;
import toby.command.commands.misc.*;
import toby.command.commands.moderation.*;
import toby.command.commands.music.*;
import toby.helpers.Cache;
import toby.jpa.dto.ConfigDto;
import toby.jpa.dto.UserDto;
import toby.jpa.service.*;

import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;

import static toby.helpers.UserDtoHelper.calculateUserDto;

@Service
@Configurable
public class CommandManager {
    private final IConfigService configService;
    private final IBrotherService brotherService;
    private final IUserService userService;
    private final List<ICommand> commands = new ArrayList<>();
    private final List<CommandData> slashCommands = new ArrayList<>();
    private final IMusicFileService musicFileService;
    private final IExcuseService excuseService;

    private final Map<User, Pair<ICommand, CommandContext>> lastCommands = new HashMap<>();

    @Autowired
    public CommandManager(IConfigService configService, IBrotherService brotherService, IUserService userService, IMusicFileService musicFileService, IExcuseService excuseService) {
        this.configService = configService;
        this.brotherService = brotherService;
        this.userService = userService;
        this.musicFileService = musicFileService;
        this.excuseService = excuseService;

        var cache = new Cache(86400, 3600, 2);

        //misc commands
        addCommand(new HelpCommand(this));
        addCommand(new RollCommand());
        addCommand(new MemeCommand());
        addCommand(new Kf2RandomMapCommand(cache));
        addCommand(new DbdRandomKillerCommand(cache));
        addCommand(new HelloThereCommand(configService));
        addCommand(new BrotherCommand(brotherService));
        addCommand(new ChCommand());
        addCommand(new UserInfoCommand(userService));
        addCommand(new RandomCommand());
        addCommand(new TeamCommand());
        //This was an example command, replace with JDA5 style with buttons later
        //        addCommand(new EventWaiterCommand(waiter));
        addCommand(new ExcuseCommand(excuseService));
        addCommand(new EightBallCommand(userService));

        //moderation commands
        addCommand(new SetConfigCommand(configService));
        addCommand(new KickCommand());
        addCommand(new MoveCommand(configService));
        addCommand(new ShhCommand());
        addCommand(new TalkCommand());
//       look at how it's done JDA5
//       addCommand(new PollCommand());
        addCommand(new AdjustUserCommand(userService));
        addCommand(new SocialCreditCommand(userService));

        //music commands
        addCommand(new JoinCommand(configService));
        addCommand(new LeaveCommand(configService));
        addCommand(new PlayCommand());
        addCommand(new NowDigOnThisCommand());
        addCommand(new SetVolumeCommand());
        addCommand(new PauseCommand());
        addCommand(new ResumeCommand());
        addCommand(new LoopCommand());
        addCommand(new StopCommand());
        addCommand(new SkipCommand());
        addCommand(new NowPlayingCommand());
        addCommand(new QueueCommand());
        addCommand(new ShuffleCommand());
        addCommand(new IntroSongCommand(userService, musicFileService, configService));
    }

    private void addCommand(ICommand cmd) {
        boolean nameFound = this.commands.stream().anyMatch((it) -> it.getName().equalsIgnoreCase(cmd.getName()));

        if (nameFound) {
            throw new IllegalArgumentException("A command with this name is already present");
        }
        commands.add(cmd);
        SlashCommandData slashCommand = cmd.getSlashCommand();
        slashCommand.addOptions(cmd.getOptionData());
        slashCommands.add(slashCommand);
    }

    public List<CommandData> getAllSlashCommands() {
        return slashCommands;
    }

    public List<ICommand> getAllCommands() {
        return commands;
    }

    public List<ICommand> getMusicCommands() {
        return commands.stream().filter(iCommand -> iCommand instanceof IMusicCommand).collect(Collectors.toList());
    }

    public List<ICommand> getModerationCommands() {
        return commands.stream().filter(iCommand -> iCommand instanceof IModerationCommand).collect(Collectors.toList());
    }

    public List<ICommand> getMiscCommands() {
        return commands.stream().filter(iCommand -> iCommand instanceof IMiscCommand).collect(Collectors.toList());
    }

    public List<ICommand> getFetchCommands() {
        return commands.stream().filter(iCommand -> iCommand instanceof IFetchCommand).collect(Collectors.toList());
    }

    @Nullable
    public ICommand getCommand(String search) {
        String searchLower = search.toLowerCase();

        for (ICommand cmd : this.commands) {
            if (cmd.getName().equals(searchLower)) {
                return cmd;
            }
        }

        return null;
    }

    public void handle(SlashCommandInteractionEvent event) {
        Integer deleteDelay = Integer.parseInt(configService.getConfigByName(ConfigDto.Configurations.DELETE_DELAY.getConfigValue(), event.getGuild().getId()).getValue());

        String volumePropertyName = ConfigDto.Configurations.VOLUME.getConfigValue();
        String defaultVolume = configService.getConfigByName(volumePropertyName, event.getGuild().getId()).getValue();
        int introVolume = Integer.parseInt(defaultVolume);

        UserDto requestingUserDto = calculateUserDto(event.getGuild().getIdLong(), event.getUser().getIdLong(), Objects.requireNonNull(event.getMember()).isOwner(), userService, introVolume);
        String invoke = event.getName().toLowerCase();
        ICommand cmd = this.getCommand(invoke);

        // Build the response embed
        if (cmd != null) {
            event.getChannel().sendTyping().queue();
            CommandContext ctx = new CommandContext(event);
            lastCommands.put(event.getUser(), Pair.of(cmd, ctx));
            cmd.handle(ctx, requestingUserDto, deleteDelay);
        }
    }

    private void attributeSocialCredit(CommandContext ctx, IUserService userService, UserDto requestingUserDto, Integer deleteDelay) {
        long socialCreditScore = requestingUserDto.getSocialCredit() == null ? 0L : requestingUserDto.getSocialCredit();
        Random r = new Random();
        int socialCredit = r.nextInt(5);
        int awardedSocialCredit = socialCredit * 5;
        requestingUserDto.setSocialCredit(socialCreditScore + awardedSocialCredit);
        userService.updateUser(requestingUserDto);
        ctx.getEvent().replyFormat("Awarded '%s' with %d social credit", ctx.getAuthor().getName(), awardedSocialCredit).queue(message -> ICommand.deleteAfter(message, deleteDelay));
    }

    public void handle(ButtonInteractionEvent event) {
        Integer deleteDelay = Integer.parseInt(configService.getConfigByName(ConfigDto.Configurations.DELETE_DELAY.getConfigValue(), event.getGuild().getId()).getValue());

        String volumePropertyName = ConfigDto.Configurations.VOLUME.getConfigValue();
        String defaultVolume = configService.getConfigByName(volumePropertyName, event.getGuild().getId()).getValue();
        int introVolume = Integer.parseInt(defaultVolume);
        UserDto requestingUserDto = calculateUserDto(event.getGuild().getIdLong(), event.getUser().getIdLong(), Objects.requireNonNull(event.getMember()).isOwner(), userService, introVolume);
        // Dispatch the simulated SlashCommandInteractionEvent
        if (event.getComponentId().equals("resend_last_request")) {
            Pair<ICommand, CommandContext> iCommandCommandContextPair = lastCommands.get(event.getUser());
            ICommand cmd = iCommandCommandContextPair.getLeft();
            // Resend the last request
            if (cmd != null) {
                event.getChannel().sendTyping().queue();
                cmd.handle(iCommandCommandContextPair.getRight(), requestingUserDto, deleteDelay);
                event.deferEdit().queue();
            }
        } else {
            //button name that should be something like 'roll: 20,1,0'
            String invoke = event.getComponentId().toLowerCase();
            String[] split = invoke.split(":");
            String commandName = split[0];
            String options = split[1];
            ICommand cmd = this.getCommand(commandName);
            if (cmd != null) {
                event.getChannel().sendTyping().queue();
                // Create a simulated SlashCommandInteractionImpl
                if (cmd.getName().equals("roll")) {
                    RollCommand rollCommand = (RollCommand) cmd;
                    String[] optionArray = options.split(",");
                    rollCommand.handleDiceRoll(event, Integer.parseInt(optionArray[0].trim()), Integer.parseInt(optionArray[1].trim()), Integer.parseInt(optionArray[2].trim())).queue(message -> ICommand.deleteAfter(message, deleteDelay));
                }
            }
            CommandContext commandContext = new CommandContext(event);
            cmd.handle(commandContext, requestingUserDto, deleteDelay);
        }
    }
}
