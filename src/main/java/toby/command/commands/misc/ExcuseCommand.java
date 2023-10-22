package toby.command.commands.misc;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Mentions;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.ExcuseDto;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IExcuseService;

import java.util.*;


public class ExcuseCommand implements IMiscCommand {

    public static final String PENDING = "pending";
    public static final String ALL = "all";
    public static final String APPROVE = "approve";
    public static final String DELETE = "delete";
    public static final String EXISTING_EXCUSE_MESSAGE = "I've heard that one before, keep up.";
    private final IExcuseService excuseService;
    private final String EXCUSE_ID = "id";
    private final String EXCUSE = "excuse";
    private final String AUTHOR = "author";
    private final String ACTION = "action";
    private final List<StringBuilder> stringBuilderList;

    public ExcuseCommand(IExcuseService excuseService) {
        this.stringBuilderList = new ArrayList<>();
        this.excuseService = excuseService;
    }

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        final SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        Long guildId = event.getGuild().getIdLong();

         if (event.getOptions().isEmpty()) {
            lookupExcuse(event, deleteDelay);
        } else {
            Optional<String> actionOptional = Optional.ofNullable(event.getOption(ACTION)).map(OptionMapping::getAsString);
            if (actionOptional.isPresent()) {
                String action = actionOptional.get();
                switch (action) {
                    case PENDING -> lookupPendingExcuses(event, guildId, deleteDelay);
                    case ALL -> listAllExcuses(event, guildId, deleteDelay);
                    case APPROVE -> {
                        Optional<Integer> excuseIdOptional = Optional.ofNullable(event.getOption(EXCUSE_ID)).map(OptionMapping::getAsInt);
                        excuseIdOptional.ifPresent(excuseId -> approvePendingExcuse(requestingUserDto, event, excuseId, deleteDelay));
                    }
                    case DELETE -> {
                        Optional<Integer> excuseIdOptional = Optional.ofNullable(event.getOption(EXCUSE_ID)).map(OptionMapping::getAsInt);
                        excuseIdOptional.ifPresent(excuseId -> deleteExcuse(requestingUserDto, event, excuseId, deleteDelay));
                    }
                }
            } else {
                List<Member> memberList = Optional.ofNullable(event.getOption(AUTHOR)).map(OptionMapping::getMentions).map(Mentions::getMembers).orElse(Collections.emptyList());
                String author = memberList.isEmpty() ? ctx.getAuthor().getName() : memberList.stream().findFirst().get().getEffectiveName();
                createNewExcuse(event, author, deleteDelay);
            }
        }
    }

    private void listAllExcuses(SlashCommandInteractionEvent event, Long guildId, Integer deleteDelay) {
        List<ExcuseDto> excuseDtos = excuseService.listApprovedGuildExcuses(guildId);
        if (excuseDtos.size() == 0) {
            event.getHook().sendMessage("There are no approved excuses, consider submitting some.").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return;
        }
        createAndAddStringBuilder();
        stringBuilderList.get(0).append("Listing all approved excuses below: \n");
        printOutExcuses(event, deleteDelay, excuseDtos);
    }

    private void createAndAddStringBuilder() {
        stringBuilderList.add(new StringBuilder(2000));
    }

    private void approvePendingExcuse(UserDto requestingUserDto, SlashCommandInteractionEvent event, int pendingExcuse, Integer deleteDelay) {
        if (requestingUserDto.isSuperUser()) {
            ExcuseDto excuseById = excuseService.getExcuseById(pendingExcuse);
            if (!excuseById.isApproved()) {
                excuseById.setApproved(true);
                excuseService.updateExcuse(excuseById);
                event.getHook().sendMessageFormat("approved excuse '%s'.", excuseById.getExcuse()).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            } else
                event.getHook().sendMessage(EXISTING_EXCUSE_MESSAGE).queue(message -> ICommand.deleteAfter(message, deleteDelay));
        } else
            sendErrorMessage(event, deleteDelay);
    }

    private void lookupExcuse(SlashCommandInteractionEvent event, Integer deleteDelay) {
        List<ExcuseDto> excuseDtos = excuseService.listApprovedGuildExcuses(event.getGuild().getIdLong());
        if (excuseDtos.size() == 0) {
            event.getHook().sendMessage("There are no approved excuses, consider submitting some.").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return;
        }
        Random random = new Random();
        ExcuseDto excuseDto = excuseDtos.get(random.nextInt(excuseDtos.size()));
        event.getHook().sendMessageFormat("Excuse #%d: '%s' - %s.", excuseDto.getId(), excuseDto.getExcuse(), excuseDto.getAuthor()).queue(message -> ICommand.deleteAfter(message, deleteDelay));
    }


    private void createNewExcuse(SlashCommandInteractionEvent event, String author, Integer deleteDelay) {
        Optional<String> excuseMessageOptional = Optional.ofNullable(event.getOption(EXCUSE)).map(OptionMapping::getAsString);
        if (excuseMessageOptional.isPresent()) {
            String excuseMessage = excuseMessageOptional.get();
            long guildId = event.getGuild().getIdLong();
            Optional<ExcuseDto> existingExcuse = excuseService.listAllGuildExcuses(guildId).stream().filter(excuseDto -> excuseDto.getExcuse().equals(excuseMessage)).findFirst();
            if (existingExcuse.isPresent()) {
                event.getHook().sendMessage(EXISTING_EXCUSE_MESSAGE).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
            } else {
                ExcuseDto excuseDto = new ExcuseDto();
                excuseDto.setGuildId(guildId);
                excuseDto.setAuthor(author);
                excuseDto.setExcuse(excuseMessage);
                ExcuseDto newExcuse = excuseService.createNewExcuse(excuseDto);
                event.getHook().sendMessageFormat("Submitted new excuse '%s' - %s with id '%d' for approval.", excuseMessage, author, newExcuse.getId()).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
            }
        }
    }

    private void lookupPendingExcuses(SlashCommandInteractionEvent event, Long guildId, Integer deleteDelay) {
        List<ExcuseDto> excuseDtos = excuseService.listPendingGuildExcuses(guildId);
        if (excuseDtos.size() == 0) {
            event.getHook().sendMessage("There are no excuses pending approval, consider submitting some.").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return;
        }
        createAndAddStringBuilder();
        stringBuilderList.get(0).append("Listing all pending excuses below: \n");
        printOutExcuses(event, deleteDelay, excuseDtos);
    }

    private void deleteExcuse(UserDto requestingUserDto, SlashCommandInteractionEvent event, int excuseId, Integer deleteDelay) {
        if (requestingUserDto.isSuperUser()) {
            excuseService.deleteExcuseById(excuseId);
            event.getHook().sendMessageFormat("deleted excuse with id '%d'.", excuseId).queue(message -> ICommand.deleteAfter(message, deleteDelay));
        } else sendErrorMessage(event, deleteDelay);
    }

    private void printOutExcuses(SlashCommandInteractionEvent event, Integer deleteDelay, List<ExcuseDto> excuseDtos) {
        excuseDtos.forEach(excuseDto -> {
            StringBuilder sb = stringBuilderList.get(stringBuilderList.size() - 1);
            String excuseString = String.format("Excuse #%d: '%s' - %s. \n", excuseDto.getId(), excuseDto.getExcuse(), excuseDto.getAuthor());
            if (sb.length() + excuseString.length() >= 2000) {
                createAndAddStringBuilder();
                sb = stringBuilderList.get(stringBuilderList.size() - 1);

            }
            sb.append(excuseString);
        });
        stringBuilderList.forEach(sb -> event.getHook().sendMessage(sb.toString()).queue(message -> ICommand.deleteAfter(message, deleteDelay)));
    }

    @Override
    public String getName() {
        return EXCUSE;
    }

    @Override
    public String getDescription() {
        return "Let me give you a convenient excuse!";
    }

    @Override
    public List<OptionData> getOptionData() {
        return List.of(
                new OptionData(OptionType.STRING, ACTION, "Which action would you like to take?")
                        .addChoice(PENDING, PENDING)
                        .addChoice(ALL, ALL)
                        .addChoice(APPROVE, APPROVE)
                        .addChoice(DELETE, DELETE),
                new OptionData(OptionType.INTEGER, EXCUSE_ID, "Use in combination with a approve action for pending IDs, or delete action for an approved ID"),
                new OptionData(OptionType.STRING, EXCUSE, "Use in combination with an approve being true. Max 200 characters"),
                new OptionData(OptionType.USER, AUTHOR, "Who made this excuse? Leave blank to attribute to the user who called the command")
        );
    }
}