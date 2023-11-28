package toby.command.commands.moderation;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Mentions;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import toby.command.CommandContext;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IUserService;

import java.util.List;
import java.util.Optional;

import static toby.command.ICommand.invokeDeleteOnMessageResponse;
import static toby.helpers.UserDtoHelper.userAdjustmentValidation;
import static toby.jpa.dto.UserDto.Permissions.*;

public class AdjustUserCommand implements IModerationCommand {

    public static final String MODIFIER = "modifier";
    private final IUserService userService;
    private final String PERMISSION_NAME = "name";
    private final String USERS = "users";

    public AdjustUserCommand(IUserService userService) {
        this.userService = userService;
    }

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        final Member member = ctx.getMember();
        Guild guild = event.getGuild();

        List<Member> mentionedMembers = channelAndArgumentValidation(event, requestingUserDto, guild.getOwner(), member, deleteDelay);
        if (mentionedMembers == null) return;

        mentionedMembers.forEach(targetMember -> {
            UserDto targetUserDto = userService.getUserById(targetMember.getIdLong(), targetMember.getGuild().getIdLong());
            //Check to see if the database contained an entry for the user we have made a request against
            if (targetUserDto != null) {
                boolean isSameGuild = requestingUserDto.getGuildId().equals(targetUserDto.getGuildId());
                boolean requesterCanAdjustPermissions = userAdjustmentValidation(requestingUserDto, targetUserDto) || member.isOwner();
                if (requesterCanAdjustPermissions && isSameGuild) {
                    validateArgumentsAndUpdateUser(event, targetUserDto, member.isOwner(), deleteDelay);
                    event.getHook().sendMessageFormat("Updated user %s's permissions", targetMember.getEffectiveName()).queue(invokeDeleteOnMessageResponse(deleteDelay));
                } else
                    event.getHook().sendMessageFormat("User '%s' is not allowed to adjust the permissions of user '%s'.", member.getEffectiveName(), targetMember.getEffectiveName()).queue(invokeDeleteOnMessageResponse(deleteDelay));

            } else {
                createNewUser(event, targetMember, deleteDelay);
            }
        });


    }

    private void validateArgumentsAndUpdateUser(SlashCommandInteractionEvent event, UserDto targetUserDto, Boolean isOwner, int deleteDelay) {
        Optional<String> permissionOptional = Optional.ofNullable(event.getOption(PERMISSION_NAME)).map(OptionMapping::getAsString);

        if (permissionOptional.isEmpty()) {
            event.getHook().sendMessage("You did not mention a valid permission to update").setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay));
            return;
        }
        Optional<Integer> modifierOptional = Optional.ofNullable(event.getOption(MODIFIER)).map(OptionMapping::getAsInt);
        modifierOptional.ifPresent(targetUserDto::setInitiativeModifier);
        switch (UserDto.Permissions.valueOf(permissionOptional.get().toUpperCase())) {
            case MUSIC -> targetUserDto.setMusicPermission(!targetUserDto.hasMusicPermission());
            case DIG -> targetUserDto.setDigPermission(!targetUserDto.hasDigPermission());
            case MEME -> targetUserDto.setMemePermission(!targetUserDto.hasMemePermission());
            case SUPERUSER -> {
                if (isOwner) targetUserDto.setSuperUser(!targetUserDto.isSuperUser());
            }
        }

        userService.updateUser(targetUserDto);
    }

    private void createNewUser(SlashCommandInteractionEvent event, Member targetMember, int deleteDelay) {
        //Database did not contain an entry for the user we have made a request against, so make one.
        UserDto newDto = new UserDto();
        newDto.setDiscordId(targetMember.getIdLong());
        newDto.setGuildId(targetMember.getGuild().getIdLong());
        userService.createNewUser(newDto);
        event.getHook().sendMessageFormat("User %s's permissions did not exist in this server's database, they have now been created", targetMember.getEffectiveName()).queue(invokeDeleteOnMessageResponse(deleteDelay));
    }

    private List<Member> channelAndArgumentValidation(SlashCommandInteractionEvent event, UserDto requestingUserDto, Member member, Member guildOwner, int deleteDelay) {
        if (!member.isOwner() && !requestingUserDto.isSuperUser()) {
            event.getHook().sendMessage(getErrorMessage(guildOwner.getEffectiveName())).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay));
            return null;
        }

        Optional<List<Member>> mentionedMembersOptional = Optional.ofNullable(event.getOption(USERS)).map(OptionMapping::getMentions).map(Mentions::getMembers);
        if (mentionedMembersOptional.isEmpty()) {
            event.getHook().sendMessage("You must mention 1 or more Users to adjust permissions of").setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay));
            return null;
        }

        if (Optional.ofNullable(event.getOption(PERMISSION_NAME)).map(OptionMapping::getAsString).isEmpty()) {
            event.getHook().sendMessage("You must mention a permission to adjust of the user you've mentioned.").setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay));
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
        OptionData permission = new OptionData(OptionType.STRING, PERMISSION_NAME, "What permission to adjust for the user", true);
        permission.addChoice(MUSIC.name(), MUSIC.name());
        permission.addChoice(MEME.name(), MEME.name());
        permission.addChoice(DIG.name(), DIG.name());
        permission.addChoice(SUPERUSER.name(), SUPERUSER.name());
        OptionData initiative = new OptionData(OptionType.INTEGER, MODIFIER, "modifier for the initiative command when used on your user");
        return List.of(userOption, permission, initiative);
    }
}
