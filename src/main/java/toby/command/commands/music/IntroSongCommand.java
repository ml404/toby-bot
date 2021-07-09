package toby.command.commands.music;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.MusicDto;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IMusicFileService;
import toby.jpa.service.IUserService;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static toby.helpers.FileUtils.readInputStreamToByteArray;

public class IntroSongCommand implements IMusicCommand {
    private final IUserService userService;
    private final IMusicFileService musicFileService;

    public IntroSongCommand(IUserService userService, IMusicFileService musicFileService) {
        this.userService = userService;
        this.musicFileService = musicFileService;
    }

    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getMessage(), deleteDelay);

        final TextChannel channel = ctx.getChannel();

        if (!requestingUserDto.isSuperUser()) {
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
                return;
            } else if (attachment.getSize() > 200000) {
                channel.sendMessage("Please keep the file size under 200kb").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            }

            List<Member> mentionedMembers = ctx.getMessage().getMentionedMembers();
            if (mentionedMembers.size() > 0) {
                mentionedMembers.forEach(member -> {
                    UserDto userDto = calculateUserDto(member);
                    persistMusicFile(userDto, deleteDelay, channel, attachment, member.getEffectiveName());
                });
            } else persistMusicFile(requestingUserDto, deleteDelay, channel, attachment, ctx.getAuthor().getName());
        }
    }


    private void persistMusicFile(UserDto targetDto, Integer deleteDelay, TextChannel channel, Message.Attachment attachment, String memberName) {
        String filename = attachment.getFileName();
        byte[] fileContents;
        try {
            fileContents = readInputStreamToByteArray(attachment.retrieveInputStream().get());
        } catch (ExecutionException | InterruptedException | IOException e) {
            channel.sendMessageFormat("Unable to read file '%s'", filename).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return;
        }
        MusicDto musicFileDto = targetDto.getMusicDto();
        if (musicFileDto == null) {
            MusicDto musicDto = new MusicDto(targetDto.getDiscordId(), targetDto.getGuildId(), filename, fileContents);
            musicFileService.createNewMusicFile(musicDto);
            targetDto.setMusicDto(musicDto);
            userService.updateUser(targetDto);
            channel.sendMessageFormat("Successfully set %s's intro song to '%s'", memberName, musicDto.getFileName()).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return;
        }
        musicFileDto.setFileName(filename);
        musicFileDto.setMusicBlob(fileContents);
        musicFileService.updateMusicFile(musicFileDto);
        channel.sendMessageFormat("Successfully updated %s's intro song to '%s'", memberName, filename).queue(message -> ICommand.deleteAfter(message, deleteDelay));
    }


    private UserDto calculateUserDto(Member member) {
        long guildId = member.getGuild().getIdLong();
        long discordId = member.getUser().getIdLong();

        Optional<UserDto> dbUserDto = userService.listGuildUsers(guildId).stream().filter(userDto -> userDto.getGuildId().equals(guildId) && userDto.getDiscordId().equals(discordId)).findFirst();
        if (dbUserDto.isEmpty()) {
            UserDto userDto = new UserDto();
            userDto.setDiscordId(discordId);
            userDto.setGuildId(guildId);
            userDto.setSuperUser(member.isOwner());
            MusicDto musicDto = new MusicDto(userDto.getDiscordId(), userDto.getGuildId(), null, null);
            userDto.setMusicDto(musicDto);
            return userService.createNewUser(userDto);
        }
        return userService.getUserById(discordId, guildId);
    }

    @Override
    public String getName() {
        return "introsong";
    }

    @Override
    public String getHelp(String prefix) {
        return "Upload a short (200kb or less) **MP3** file for Toby to sing when you join a voice channel (and he's not currently in a voice channel playing music) \n" +
                String.format("Usage: %sintrosong with a file attached to your message", prefix) +
                String.format("Aliases are: '%s'", String.join(",", getAliases()));
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("intro", "setintro");
    }
}
