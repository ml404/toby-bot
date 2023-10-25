package toby.command.commands.misc;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Mentions;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import toby.command.CommandContext;
import toby.jpa.dto.MusicDto;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IUserService;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static toby.command.ICommand.deleteAfter;
import static toby.command.ICommand.getConsumer;


public class UserInfoCommand implements IMiscCommand {


    private final String USERS = "users";
    private final IUserService userService;

    public UserInfoCommand(IUserService userService) {

        this.userService = userService;
    }

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        final SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        printUserInfo(event, requestingUserDto, deleteDelay);
    }

    private void printUserInfo(SlashCommandInteractionEvent event, UserDto requestingUserDto, Integer deleteDelay) {
        if (event.getOptions().isEmpty()) {
            if (requestingUserDto != null) {
                event.getHook().sendMessageFormat("Here are your permissions: '%s'.", requestingUserDto).setEphemeral(true).queue(getConsumer(deleteDelay));
                MusicDto musicDto = requestingUserDto.getMusicDto();
                if (musicDto != null) {
                    if (musicDto.getFileName() == null || musicDto.getFileName().isBlank()) {
                        event.getHook().sendMessage("There is no intro music file associated with your user.").setEphemeral(true).queue(getConsumer(deleteDelay));

                    } else if (musicDto.getFileName() != null) {
                        event.getHook().sendMessageFormat("Your intro song is currently set as: '%s'.", musicDto.getFileName()).setEphemeral(true).queue(getConsumer(deleteDelay));
                    }
                } else
                    event.getHook().sendMessage("I was unable to retrieve your music file.").setEphemeral(true).queue(getConsumer(deleteDelay));
            }
        } else {
            if (requestingUserDto.isSuperUser()) {
                List<Member> memberList = Optional.ofNullable(event.getOption(USERS)).map(OptionMapping::getMentions).map(Mentions::getMembers).orElse(Collections.emptyList());
                memberList.forEach(member -> {
                    UserDto mentionedUser = userService.getUserById(member.getIdLong(), member.getGuild().getIdLong());
                    event.getHook().sendMessageFormat("Here are the permissions for '%s': '%s'.", member.getEffectiveName(), mentionedUser).setEphemeral(true).queue(getConsumer(deleteDelay));
                    MusicDto musicDto = mentionedUser.getMusicDto();
                    if (musicDto != null) {
                        if (musicDto.getFileName() == null || musicDto.getFileName().isBlank()) {
                            event.getHook().sendMessageFormat("There is no intro music file associated with '%s'.", member.getEffectiveName()).setEphemeral(true).queue(getConsumer(deleteDelay));

                        } else if (musicDto.getFileName() != null) {
                            event.getHook().sendMessageFormat("Their intro song is currently set as: '%s'.", musicDto.getFileName()).setEphemeral(true).queue(getConsumer(deleteDelay));
                        }
                    } else
                        event.getHook().sendMessageFormat("I was unable to retrieve an associated music file for '%s'.", member.getEffectiveName()).setEphemeral(true).queue(getConsumer(deleteDelay));
                });
            }
            else event.getHook().sendMessage("You do not have permission to view user permissions, if this is a mistake talk to the server owner").setEphemeral(true).queue();
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