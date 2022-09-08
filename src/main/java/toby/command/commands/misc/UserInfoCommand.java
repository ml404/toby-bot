package toby.command.commands.misc;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.MusicDto;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IUserService;

import java.util.List;


public class UserInfoCommand implements IMiscCommand {


    private final String USERS = "users";
    private IUserService userService;

    public UserInfoCommand(IUserService userService) {

        this.userService = userService;
    }

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        final SlashCommandInteractionEvent event = ctx.getEvent();

        printUserInfo(event, requestingUserDto, deleteDelay);
    }

    private void printUserInfo(SlashCommandInteractionEvent event, UserDto requestingUserDto, Integer deleteDelay) {
        if (event.getOptions().isEmpty()) {
            if (requestingUserDto != null) {
                event.replyFormat("Here are your permissions: '%s'.", requestingUserDto).setEphemeral(true).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
                MusicDto musicDto = requestingUserDto.getMusicDto();
                if (musicDto != null) {
                    if (musicDto.getFileName() == null || musicDto.getFileName().isBlank()) {
                        event.reply("There is no intro music file associated with your user.").setEphemeral(true).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));

                    } else if (musicDto.getFileName() != null) {
                        event.replyFormat("Your intro song is currently set as: '%s'.", musicDto.getFileName()).setEphemeral(true).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
                    }
                } else
                    event.reply("I was unable to retrieve your music file.").setEphemeral(true).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
            }
        } else {
            if (requestingUserDto.isSuperUser()) {
                event.getOption(USERS).getMentions().getMembers().stream().forEach(member -> {
                    UserDto mentionedUser = userService.getUserById(member.getIdLong(), member.getGuild().getIdLong());
                    event.replyFormat("Here are the permissions for '%s': '%s'.", member.getEffectiveName(), mentionedUser).setEphemeral(true).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
                    MusicDto musicDto = mentionedUser.getMusicDto();
                    if (musicDto != null) {
                        if (musicDto.getFileName() == null || musicDto.getFileName().isBlank()) {
                            event.replyFormat("There is no intro music file associated with '%s'.", member.getEffectiveName()).setEphemeral(true).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));

                        } else if (musicDto.getFileName() != null) {
                            event.replyFormat("Their intro song is currently set as: '%s'.", musicDto.getFileName()).setEphemeral(true).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
                        }
                    } else
                        event.replyFormat("I was unable to retrieve an associated music file for '%s'.", member.getNickname()).setEphemeral(true).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
                });
            }
            else event.reply("You do not have permission to view user permissions, if this is a mistake talk to the server owner").setEphemeral(true).queue();
        }
    }

    @Override
    public String getName() {
        return "userinfo";
    }

    @Override
    public String getDescription() {
        return "Let me tell you about the permissions tied to the user mentioned (no mention is your own).";
    }

    @Override
    public List<OptionData> getOptionData() {
        return List.of(new OptionData(OptionType.STRING, USERS, "List of users to print info about"));
    }
}