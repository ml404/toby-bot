package toby.command.commands.moderation;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.jetbrains.annotations.Nullable;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IUserService;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static toby.helpers.UserDtoHelper.userAdjustmentValidation;
import static toby.jpa.dto.ConfigDto.Configurations.values;

public class AdjustUserCommand implements IModerationCommand {

    private final IUserService userService;
    private final String PERMISSION_NAME = "name";
    private final String PERMISSION_VALUE = "value";
    private final String USERS = "users";

    public AdjustUserCommand(IUserService userService) {
        this.userService = userService;
    }

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        final Member member = ctx.getMember();

        List<Member> mentionedMembers = channelAndArgumentValidation(requestingUserDto, event, member, deleteDelay);
        if (mentionedMembers == null) return;

        mentionedMembers.forEach(targetMember -> {
            UserDto targetUserDto = userService.getUserById(targetMember.getIdLong(), targetMember.getGuild().getIdLong());
            //Check to see if the database contained an entry for the user we have made a request against
            if (targetUserDto != null) {
                boolean isSameGuild = requestingUserDto.getGuildId().equals(targetUserDto.getGuildId());
                boolean requesterCanAdjustPermissions = userAdjustmentValidation(requestingUserDto, targetUserDto) || member.isOwner();
                if (requesterCanAdjustPermissions && isSameGuild) {
                    validateArgumentsAndUpdateUser(event, targetUserDto, member.isOwner(), deleteDelay);
                    event.getHook().sendMessageFormat("Updated user %s's permissions", targetMember.getNickname()).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
                } else
                    event.getHook().sendMessageFormat("User '%s' is not allowed to adjust the permissions of user '%s'.", member.getNickname(), targetMember.getNickname()).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));

            } else {
                createNewUser(event, targetMember, deleteDelay);
            }
        });


    }

    private void validateArgumentsAndUpdateUser(SlashCommandInteractionEvent event, UserDto targetUserDto, Boolean isOwner, int deleteDelay) {
        Optional<String> permissionNameOptional = Optional.ofNullable(event.getOption(PERMISSION_NAME).getAsString());
        Optional<Boolean> permissionValueOptional = Optional.ofNullable(event.getOption(PERMISSION_VALUE).getAsBoolean());
        

        if (permissionNameOptional.isEmpty() || permissionValueOptional.isEmpty()) {
            event.getHook().sendMessage("You did not mention a valid permission to update, or give it a value").setEphemeral(true).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return;
        }
        String permissionName = permissionNameOptional.get();
        Boolean permissionValue = permissionValueOptional.get();
        if (permissionName.equals(UserDto.Permissions.MUSIC.name()))
            targetUserDto.setMusicPermission(permissionValue);
        if (permissionName.equals(UserDto.Permissions.DIG.name()))
            targetUserDto.setDigPermission(permissionValue);
        if (permissionName.equals(UserDto.Permissions.MEME.name()))
            targetUserDto.setMemePermission(permissionValue);
        if (permissionName.equals(UserDto.Permissions.SUPERUSER.name()) && isOwner)
            targetUserDto.setSuperUser(permissionValue);

        userService.updateUser(targetUserDto);
    }

    private void createNewUser(SlashCommandInteractionEvent event, Member targetMember, int deleteDelay) {
        //Database did not contain an entry for the user we have made a request against, so make one.
        UserDto newDto = new UserDto();
        newDto.setDiscordId(targetMember.getIdLong());
        newDto.setGuildId(targetMember.getGuild().getIdLong());
        userService.createNewUser(newDto);
        event.getHook().sendMessageFormat("User %s's permissions did not exist in this server's database, they have now been created", targetMember.getNickname()).queue(message -> ICommand.deleteAfter(message, deleteDelay));
    }

    @Nullable
    private List<Member> channelAndArgumentValidation(UserDto requestingUserDto, SlashCommandInteractionEvent event, Member member, int deleteDelay) {
        if (!member.isOwner() && !requestingUserDto.isSuperUser()) {
            event.getHook().sendMessage("This command is reserved for the owner of the server and users marked as super users only, this may change in the future").setEphemeral(true).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
            return null;
        }

        Optional<List<Member>> mentionedMembersOptional = Optional.ofNullable(event.getOption(USERS).getMentions().getMembers());
        if (mentionedMembersOptional.isEmpty()) {
            event.getHook().sendMessage("You must mention 1 or more Users to adjust permissions of").setEphemeral(true).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
            return null;
        }

        if (Optional.ofNullable(event.getOption(PERMISSION_NAME).getAsString()).isEmpty()) {
            event.getHook().sendMessage("You must mention 1 or more permissions to adjust of the user you've mentioned.").setEphemeral(true).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
            return null;
        }
        return mentionedMembersOptional.get();
    }

    @Override
    public String getName() {
        return "adjustuser";
    }

    @Override
    public String getDescription() {
        return "Use this command to adjust the mentioned user's permissions to use TobyBot commands for your server";
    }

    @Override
    public List<OptionData> getOptionData() {
        OptionData userOption = new OptionData(OptionType.STRING, USERS, "User(s) who you would like to adjust the permissions of.", true);
        OptionData permissionName = new OptionData(OptionType.STRING, PERMISSION_NAME, "What permission to adjust for the user", true);
        OptionData permissionValue = new OptionData(OptionType.BOOLEAN, PERMISSION_VALUE, "Value for the permission you want to adjust (true/false)", true);
        Arrays.stream(values()).forEach(conf -> permissionName.addChoice(conf.getConfigValue(), conf.getConfigValue()));
        return List.of(
                userOption,
                permissionName,
                permissionValue);
    }
}
