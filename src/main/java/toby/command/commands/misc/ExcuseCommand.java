package toby.command.commands.misc;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.ExcuseDto;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IExcuseService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;


public class ExcuseCommand implements IMiscCommand {

    public static final String PENDING = "Pending";
    public static final String ALL = "All";
    public static final String APPROVE = "Approve";
    public static final String DELETE = "Delete";
    private final IExcuseService excuseService;
    private List<StringBuilder> stringBuilderList;

    public ExcuseCommand(IExcuseService excuseService) {

        this.excuseService = excuseService;
    }

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        final SlashCommandInteractionEvent event = ctx.getEvent();
        Long guildId = event.getGuild().getIdLong();

        if (event.getOptions().isEmpty()) {
            lookupExcuse(event, deleteDelay);
        } else {
            String action = event.getOption("Action").getAsString();
            if (action.equals(PENDING)) {
                lookupPendingExcuses(event, guildId, deleteDelay);
            } else if (action.equals(ALL)) {
                listAllExcuses(event, guildId, deleteDelay);
            } else if (action.equals(APPROVE)) {
                approvePendingExcuse(requestingUserDto, event, event.getOption("Excuse ID").getAsString(), deleteDelay);
            } else if (action.equals(DELETE)) {
                deleteExcuse(requestingUserDto, event, event.getOption("Excuse ID").getAsInt(), deleteDelay);
            } else {
                OptionMapping authorOptionMapping = event.getOption("Author");
                String author = authorOptionMapping.getMentions().getMembers().isEmpty() ? ctx.getAuthor().getName() : event.getOptionsByType(OptionType.MENTIONABLE).stream().map(OptionMapping::getAsMember).findFirst().get().getEffectiveName();
                createNewExcuse(event, author, deleteDelay);
            }
        }
    }

    private void listAllExcuses(SlashCommandInteractionEvent event, Long guildId, Integer deleteDelay) {
        List<ExcuseDto> excuseDtos = excuseService.listApprovedGuildExcuses(guildId);
        if (excuseDtos.size() == 0) {
            event.reply("There are no approved excuses, consider submitting some.").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return;
        }
        event.reply("Listing all approved excuses below:").queue(message -> ICommand.deleteAfter(message, deleteDelay));
        stringBuilderList = new ArrayList<>();
        createAndAddStringBuilder();
        excuseDtos.forEach(excuseDto -> {
            StringBuilder sb = stringBuilderList.get(stringBuilderList.size() - 1);
            String excuseString = String.format("Excuse #%d: '%s' - %s. \n", excuseDto.getId(), excuseDto.getExcuse(), excuseDto.getAuthor());
            if (sb.length() + excuseString.length() >= 2000) {
                createAndAddStringBuilder();
                sb = stringBuilderList.get(stringBuilderList.size() - 1);

            }
            sb.append(excuseString);
        });
        stringBuilderList.forEach(sb -> event.reply(sb.toString()).queue(message -> ICommand.deleteAfter(message, deleteDelay)));
    }

    private List<StringBuilder> createAndAddStringBuilder() {
        stringBuilderList.add(new StringBuilder(2000));
        return stringBuilderList;
    }

    private void approvePendingExcuse(UserDto requestingUserDto, SlashCommandInteractionEvent event, String pendingExcuse, Integer deleteDelay) {
        if (requestingUserDto.isSuperUser()) {
            String excuseId = pendingExcuse.split(" ", 3)[2];
            ExcuseDto excuseById = excuseService.getExcuseById(Integer.parseInt(excuseId));
            if (!excuseById.isApproved()) {
                excuseById.setApproved(true);
                excuseService.updateExcuse(excuseById);
                event.replyFormat("Approved excuse '%s'.", excuseById.getExcuse()).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            } else
                event.reply("I've heard that excuse before, keep up.").queue(message -> ICommand.deleteAfter(message, deleteDelay));
        } else
            sendErrorMessage(event, deleteDelay);
    }

    private void lookupExcuse(SlashCommandInteractionEvent event, Integer deleteDelay) {
        List<ExcuseDto> excuseDtos = excuseService.listApprovedGuildExcuses(event.getGuild().getIdLong());
        if (excuseDtos.size() == 0) {
            event.reply("There are no approved excuses, consider submitting some.").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return;
        }
        Random random = new Random();
        ExcuseDto excuseDto = excuseDtos.get(random.nextInt(excuseDtos.size()));
        event.replyFormat("Excuse #%d: '%s' - %s.", excuseDto.getId(), excuseDto.getExcuse(), excuseDto.getAuthor()).queue(message -> ICommand.deleteAfter(message, deleteDelay));
    }


    private void createNewExcuse(SlashCommandInteractionEvent event, String author, Integer deleteDelay) {
        String excuseMessage = event.getOption("Excuse").getAsString();
        long guildId = event.getGuild().getIdLong();
        Optional<ExcuseDto> existingExcuse = excuseService.listAllGuildExcuses(guildId).stream().filter(excuseDto -> excuseDto.getExcuse().equals(excuseMessage)).findFirst();
        if (existingExcuse.isPresent()) {
            event.reply("I've heard that one before, keep up.").queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
        } else {
            ExcuseDto excuseDto = new ExcuseDto();
            excuseDto.setGuildId(guildId);
            excuseDto.setAuthor(author);
            excuseDto.setExcuse(excuseMessage);
            ExcuseDto newExcuse = excuseService.createNewExcuse(excuseDto);
            event.replyFormat("Submitted new excuse '%s' - %s with id '%d' for approval.", excuseMessage, author, newExcuse.getId()).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
        }
    }

    private void lookupPendingExcuses(SlashCommandInteractionEvent event, Long guildId, Integer deleteDelay) {
        List<ExcuseDto> excuseDtos = excuseService.listPendingGuildExcuses(guildId);
        if (excuseDtos.size() == 0) {
            event.reply("There are no excuses pending approval, consider submitting some.").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return;
        }
        Random random = new Random();
        ExcuseDto excuseDto = excuseDtos.get(random.nextInt(excuseDtos.size()));
        event.replyFormat("Excuse #%d: '%s' - %s.", excuseDto.getId(), excuseDto.getExcuse(), excuseDto.getAuthor()).queue(message -> ICommand.deleteAfter(message, deleteDelay));
    }

    private void deleteExcuse(UserDto requestingUserDto, SlashCommandInteractionEvent event, int excuseId, Integer deleteDelay) {
        if (requestingUserDto.isSuperUser()) {
            excuseService.deleteExcuseById(excuseId);
            event.replyFormat("Deleted excuse with id '%d'.", excuseId).queue(message -> ICommand.deleteAfter(message, deleteDelay));
        } else sendErrorMessage(event, deleteDelay);
    }

    @Override
    public String getName() {
        return "excuse";
    }

    @Override
    public String getDescription() {
        return "Let me give you a convenient excuse!";
    }

    @Override
    public List<OptionData> getOptionData() {
        return List.of(
                new OptionData(OptionType.STRING, "Action", "Which action would you like to take?")
                        .addChoice("Pending", "Pending")
                        .addChoice("All", "All")
                        .addChoice("Approve", "Approve")
                        .addChoice("Delete", "Delete"),
                new OptionData(OptionType.INTEGER, "Excuse ID", "Use in combination with a Approve action for pending IDs, or Delete action for an approved ID"),
                new OptionData(OptionType.STRING, "Excuse", "Approve excuse? Use in combination with a Approve being true. Max 200 characters. Can optionally mention a user to attribute the quote to"),
                new OptionData(OptionType.USER, "Author", "Who made this excuse? Leave blank to attribute to the user who called the command")
                );
    }
}