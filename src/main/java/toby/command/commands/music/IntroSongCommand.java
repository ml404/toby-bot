package toby.command.commands.music;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.MusicDto;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IMusicFileService;
import toby.jpa.service.IUserService;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public class IntroSongCommand implements IMusicCommand {
    private final IUserService userService;
    private IMusicFileService musicFileService;

    public IntroSongCommand(IUserService userService, IMusicFileService musicFileService) {
        this.userService = userService;
        this.musicFileService = musicFileService;
    }

    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getMessage(), deleteDelay);

        final TextChannel channel = ctx.getChannel();

        if (!requestingUserDto.hasMusicPermission()) {
            sendErrorMessage(ctx, channel, deleteDelay);
            return;
        }

        List<Message.Attachment> attachments = ctx.getMessage().getAttachments();

        if (attachments.isEmpty()) {
            channel.sendMessage(getHelp(prefix)).queue(message -> ICommand.deleteAfter(message, deleteDelay));
        } else {
            Message.Attachment attachment = attachments.stream().findFirst().get();
            if (!Objects.equals(attachment.getFileExtension(), "mp3")) {
                channel.sendMessage("Please use mp3 files only").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            } else if (attachment.getSize() > 200000) {
                channel.sendMessage("Please keep the file size under 200kb").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            } else {
                String filename = attachment.getFileName();
                String fileContents;
                try {
                    fileContents = readAttachmentContents(attachment);
                } catch (ExecutionException | InterruptedException e) {
                    channel.sendMessageFormat("Unable to read file '%s'", filename).queue(message -> ICommand.deleteAfter(message, deleteDelay));
                    return;
                }
                MusicDto musicFileDto = requestingUserDto.getMusicDto();
                if (musicFileDto == null) {
                    MusicDto musicDto = new MusicDto(requestingUserDto.getDiscordId(), requestingUserDto.getGuildId(), filename, fileContents);
                    musicFileService.createNewMusicFile(musicDto);
                    requestingUserDto.setMusicDto(musicDto);
                    userService.updateUser(requestingUserDto);
                    channel.sendMessageFormat("Successfully set %s's intro song to '%s'", ctx.getAuthor().getName(), musicDto.getFileName()).queue(message -> ICommand.deleteAfter(message, deleteDelay));
                    return;
                }
                musicFileDto.setFileName(filename);
                musicFileDto.setMusicBlob(fileContents);
                requestingUserDto.setMusicDto(musicFileDto);
                userService.updateUser(requestingUserDto);
                channel.sendMessageFormat("Successfully updated %s's intro song to '%s'", ctx.getAuthor().getName(), filename).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            }
        }

    }

    private String readAttachmentContents(Message.Attachment attachment) throws ExecutionException, InterruptedException {
        InputStream inputStream = attachment.retrieveInputStream().get();
        return new BufferedReader(new InputStreamReader(inputStream))
                .lines().collect(Collectors.joining("\n"));

    }

    @Override
    public String getName() {
        return "introsong";
    }

    @Override
    public String getHelp(String prefix) {
        return "Upload a short (200kb or less) **MP3** file for Toby to sing when you join a voice channel (and he's not currently in a voice channel playing music) \n" +
                String.format("Usage: %sintrosong with a file attached to your message", prefix);
    }

    @Override
    public List<String> getAliases() {
        return IMusicCommand.super.getAliases();
    }
}
