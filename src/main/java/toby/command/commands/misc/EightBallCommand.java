package toby.command.commands.misc;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IUserService;

import java.util.Random;

public class EightBallCommand implements IMiscCommand {


    public static final long TOMS_DISCORD_ID = 312691905030782977L;
    private final IUserService userService;

    public EightBallCommand(IUserService userService) {

        this.userService = userService;
    }

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        ICommand.deleteAfter(event.getHook(), deleteDelay);

        Random r = new Random();

        int choice = 1 + r.nextInt(20);
        String response = switch (choice) {
            case 1 -> "It is certain";
            case 2 -> "It is decidedly so";
            case 3 -> "Without a doubt";
            case 4 -> "Yes - definitely";
            case 5 -> "You may rely on it";
            case 6 -> "As I see it, yes";
            case 7 -> "Most likely";
            case 8 -> "Outlook good";
            case 9 -> "Signs point to yes";
            case 10 -> "Yes";
            case 11 -> "Reply hazy, try again";
            case 12 -> "Ask again later";
            case 13 -> "Better not tell you now";
            case 14 -> "Cannot predict now";
            case 15 -> "Concentrate and ask again";
            case 16 -> "Don't count on it";
            case 17 -> "My reply is no";
            case 18 -> "My sources say no";
            case 19 -> "Outlook not so good";
            case 20 -> "Very doubtful";
            default -> "I fucked up, please try again";
        };

        if(requestingUserDto.getDiscordId().equals(TOMS_DISCORD_ID)){
            event.replyFormat("MAGIC 8-BALL SAYS: Don't fucking talk to me.").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            Long socialCredit = requestingUserDto.getSocialCredit();
            int deductedSocialCredit = -5 * choice;
            requestingUserDto.setSocialCredit(socialCredit + deductedSocialCredit);
            event.replyFormat("Deducted: %d social credit.", deductedSocialCredit).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            userService.updateUser(requestingUserDto);
            return;
        }
        event.replyFormat("MAGIC 8-BALL SAYS: %s.", response).queue(message -> ICommand.deleteAfter(message, deleteDelay));
    }

    @Override
    public String getName() {
        return "8ball";
    }

    @Override
    public String getDescription() {
        return "Think of a question and let me divine to you an answer!";
    }

}

