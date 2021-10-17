package toby.command.commands.misc;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.ExcuseDto;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IExcuseService;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;


public class ExcuseCommand implements IMiscCommand {

    private final IExcuseService excuseService;

    public ExcuseCommand(IExcuseService excuseService) {

        this.excuseService = excuseService;
    }

    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getMessage(), deleteDelay);
        final TextChannel channel = ctx.getChannel();
        final Message message = ctx.getMessage();
        Long guildId = message.getGuild().getIdLong();
        List<String> args = ctx.getArgs();

        if (args.isEmpty()) {
            lookupExcuse(channel, guildId, deleteDelay);
        } else if (args.contains("pending")) {
            lookupPendingExcuses(channel, guildId, deleteDelay);
        } else if (args.contains("approve")) {
            approvePendingExcuse(ctx, requestingUserDto, channel, message.getContentRaw(), deleteDelay);
        } else {
            String author = message.getMentionedMembers().size() > 0 ? message.getMentionedMembers().stream().findFirst().get().getNickname() : ctx.getAuthor().getName();
            createNewExcuse(channel, guildId, author, args, deleteDelay);
        }
    }

    private void approvePendingExcuse(CommandContext ctx, UserDto requestingUserDto, TextChannel channel, String pendingExcuse, Integer deleteDelay) {
        if (requestingUserDto.isSuperUser()) {
            String excuseId = pendingExcuse.split(" ", 3)[2];
            ExcuseDto excuseById = excuseService.getExcuseById(Integer.parseInt(excuseId));
            if (!excuseById.isApproved()) {
                excuseById.setApproved(true);
                excuseService.updateExcuse(excuseById);
                channel.sendMessage(String.format("Approved excuse '%s'.", excuseById.getExcuse())).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            } else
                channel.sendMessage("I've heard that excuse before, keep up.").queue(message -> ICommand.deleteAfter(message, deleteDelay));
        } else
            sendErrorMessage(ctx, channel, deleteDelay);
    }

    private void lookupExcuse(TextChannel channel, Long guildId, Integer deleteDelay) {
        List<ExcuseDto> excuseDtos = excuseService.listApprovedGuildExcuses(guildId);
        if (excuseDtos.size() == 0) {
            channel.sendMessage("There are no approved excuses, consider submitting some.").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return;
        }
        Random random = new Random();
        ExcuseDto excuseDto = excuseDtos.get(random.nextInt(excuseDtos.size()));
        channel.sendMessage(String.format("Excuse #%d: '%s' - %s.", excuseDto.getId(), excuseDto.getExcuse(), excuseDto.getAuthor())).queue(message -> ICommand.deleteAfter(message, deleteDelay));
    }


    private void createNewExcuse(TextChannel channel, Long guildId, String author, List<String> args, Integer deleteDelay) {
        List<String> mentionlessList = args.subList(1, args.size()+1).stream().filter(s -> !s.matches(Message.MentionType.USER.getPattern().pattern())).collect(Collectors.toList());
        String excuseMessage = String.join(" ", mentionlessList);
        Optional<ExcuseDto> existingExcuse = excuseService.listAllGuildExcuses(guildId).stream().filter(excuseDto -> excuseDto.getExcuse().equals(excuseMessage)).findFirst();
        if (existingExcuse.isPresent()) {
            channel.sendMessage("I've heard that one before, keep up.").queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
        } else {
            ExcuseDto excuseDto = new ExcuseDto();
            excuseDto.setGuildId(guildId);
            excuseDto.setAuthor(author);
            excuseDto.setExcuse(excuseMessage);
            ExcuseDto newExcuse = excuseService.createNewExcuse(excuseDto);
            channel.sendMessage(String.format("Submitted new excuse '%s' with id '%d' for approval.", excuseMessage, newExcuse.getId())).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
        }
    }

    private void lookupPendingExcuses(TextChannel channel, Long guildId, Integer deleteDelay) {
        List<ExcuseDto> excuseDtos = excuseService.listPendingGuildExcuses(guildId);
        if (excuseDtos.size() == 0) {
            channel.sendMessage("There are no excuses pending approval, consider submitting some.").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return;
        }
        Random random = new Random();
        ExcuseDto excuseDto = excuseDtos.get(random.nextInt(excuseDtos.size()));
        channel.sendMessage(String.format("Excuse #%d: '%s' - %s.", excuseDto.getId(), excuseDto.getExcuse(), excuseDto.getAuthor())).queue(message -> ICommand.deleteAfter(message, deleteDelay));
    }

    @Override
    public String getName() {
        return "excuse";
    }

    @Override
    public String getHelp(String prefix) {
        return "Let me give you a convenient excuse! \n" +
                String.format("Usages: `%sexcuse` to list a random approved excuse \n", prefix) +
                String.format("`%sexcuse exampleExcuseMessage` to submit an excuse to be approved, max 200 characters. Can optionally mention a user to attribute the quote to. \n", prefix) +
                String.format("`%sexcuse pending` to list pending excuses. \n", prefix) +
                String.format("`%sexcuse approve $pendingExcuseNumber` to approve a pending excuse. \n", prefix);
    }
}