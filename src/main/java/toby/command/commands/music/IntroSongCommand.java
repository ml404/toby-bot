package toby.command.commands.music;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.helpers.URLHelper;
import toby.jpa.dto.ConfigDto;
import toby.jpa.dto.MusicDto;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IConfigService;
import toby.jpa.service.IMusicFileService;
import toby.jpa.service.IUserService;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static toby.helpers.FileUtils.readInputStreamToByteArray;
import static toby.helpers.UserDtoHelper.calculateUserDto;

public class IntroSongCommand implements IMusicCommand {
    private final IUserService userService;
    private final IMusicFileService musicFileService;
    private IConfigService configService;

    public IntroSongCommand(IUserService userService, IMusicFileService musicFileService, IConfigService configService) {
        this.userService = userService;
        this.musicFileService = musicFileService;
        this.configService = configService;
    }

    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getMessage(), deleteDelay);
        String volumePropertyName = ConfigDto.Configurations.VOLUME.getConfigValue();
        String defaultVolume = configService.getConfigByName(volumePropertyName, ctx.getGuild().getId()).getValue();
        String vol = ctx.getArgs().stream().filter(s -> s.matches("\\d+")).findFirst().orElse(defaultVolume);
        int introVolume = Integer.parseInt(vol);
        if (introVolume < 1) introVolume = 1;
        if (introVolume > 100) introVolume = 100;

        final TextChannel channel = ctx.getChannel();
        if (!requestingUserDto.isSuperUser() && ctx.getMessage().getMentionedMembers().size() > 0) {
            sendErrorMessage(ctx, channel, deleteDelay);
            return;
        }
        List<Message.Attachment> attachments = ctx.getMessage().getAttachments();
        List<URI> urlList = ctx.getArgs().stream().map(URLHelper::isValidURL).filter(Objects::nonNull).findFirst().stream().collect(Collectors.toList());
        if (attachments.isEmpty() && urlList.isEmpty()) {
            channel.sendMessage(getHelp(prefix)).queue(message -> ICommand.deleteAfter(message, deleteDelay));
        } else if (attachments.isEmpty()) {
            setIntroViaUrl(ctx, requestingUserDto, deleteDelay, channel, urlList, introVolume);
        } else {
            setIntroViaDiscordAttachment(ctx, requestingUserDto, deleteDelay, channel, attachments, introVolume);
        }
    }

    private void setIntroViaDiscordAttachment(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay, TextChannel channel, List<Message.Attachment> attachments, int introVolume) {
        Message.Attachment attachment = attachments.stream().findFirst().get();
        if (!Objects.equals(attachment.getFileExtension(), "mp3")) {
            channel.sendMessage("Please use mp3 files only").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return;
        } else if (attachment.getSize() > 300000) {
            channel.sendMessage("Please keep the file size under 300kb").queue(message -> ICommand.deleteAfter(message, deleteDelay));
        }

        List<Member> mentionedMembers = ctx.getMessage().getMentionedMembers();
        InputStream inputStream = null;
        try {
            inputStream = attachment.retrieveInputStream().get();
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }
        if (mentionedMembers.size() > 0 && requestingUserDto.isSuperUser()) {
            InputStream finalInputStream = inputStream;
            mentionedMembers.forEach(member -> {
                UserDto userDto = calculateUserDto(member.getGuild().getIdLong(), member.getIdLong(), member.isOwner(), userService, introVolume);
                persistMusicFile(userDto, deleteDelay, channel, attachment.getFileName(), introVolume, finalInputStream, member.getEffectiveName());
            });
        } else
            persistMusicFile(requestingUserDto, deleteDelay, channel, attachment.getFileName(), introVolume, inputStream, ctx.getAuthor().getName());
    }

    private void setIntroViaUrl(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay, TextChannel channel, List<URI> urlList, int introVolume) {
        List<Member> mentionedMembers = ctx.getMessage().getMentionedMembers();
        String url = urlList.get(0).toString();

        if (mentionedMembers.size() > 0 && requestingUserDto.isSuperUser()) {
            mentionedMembers.forEach(member -> {
                UserDto userDto = calculateUserDto(member.getGuild().getIdLong(), member.getIdLong(), member.isOwner(), userService, introVolume);
                persistMusicUrl(userDto, deleteDelay, channel, url, url, member.getEffectiveName(), introVolume);
            });
        } else
            persistMusicUrl(requestingUserDto, deleteDelay, channel, url, url, ctx.getAuthor().getName(), introVolume);
    }


    private void persistMusicFile(UserDto targetDto, Integer deleteDelay, TextChannel channel, String filename, int introVolume, InputStream inputStream, String memberName) {
        byte[] fileContents;
        try {
            fileContents = readInputStreamToByteArray(inputStream);
        } catch (ExecutionException | InterruptedException | IOException e) {
            channel.sendMessageFormat("Unable to read file '%s'", filename).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return;
        }
        MusicDto musicFileDto = targetDto.getMusicDto();
        if (musicFileDto == null) {
            MusicDto musicDto = new MusicDto(targetDto.getDiscordId(), targetDto.getGuildId(), filename, introVolume, fileContents);
            musicFileService.createNewMusicFile(musicDto);
            targetDto.setMusicDto(musicDto);
            userService.updateUser(targetDto);
            channel.sendMessageFormat("Successfully set %s's intro song to '%s' with volume '%d'", memberName, musicDto.getFileName(), musicDto.getIntroVolume()).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return;
        }
        musicFileDto.setFileName(filename);
        musicFileDto.setIntroVolume(introVolume);
        musicFileDto.setMusicBlob(fileContents);
        musicFileService.updateMusicFile(musicFileDto);
        channel.sendMessageFormat("Successfully updated %s's intro song to '%s' with volume '%d'", memberName, filename, introVolume).queue(message -> ICommand.deleteAfter(message, deleteDelay));
    }

    private void persistMusicUrl(UserDto targetDto, Integer deleteDelay, TextChannel channel, String filename, String url, String memberName, int introVolume) {
        MusicDto musicFileDto = targetDto.getMusicDto();
        byte[] urlBytes = url.getBytes();
        if (musicFileDto == null) {
            MusicDto musicDto = new MusicDto(targetDto.getDiscordId(), targetDto.getGuildId(), filename, introVolume, urlBytes);
            musicFileService.createNewMusicFile(musicDto);
            targetDto.setMusicDto(musicDto);
            userService.updateUser(targetDto);
            channel.sendMessageFormat("Successfully set %s's intro song to '%s'", memberName, musicDto.getFileName()).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return;
        }
        musicFileDto.setFileName(filename);
        musicFileDto.setIntroVolume(introVolume);
        musicFileDto.setMusicBlob(urlBytes);
        musicFileService.updateMusicFile(musicFileDto);
        channel.sendMessageFormat("Successfully updated %s's intro song to '%s'", memberName, filename).queue(message -> ICommand.deleteAfter(message, deleteDelay));
    }


    @Override
    public String getName() {
        return "introsong";
    }

    @Override
    public String getHelp(String prefix) {
        return "Upload a short (200kb or less) **MP3** file for Toby to sing when you join a voice channel (and he's not currently in a voice channel playing music). Also works with youtube video links instead of file. \n" +
                String.format("Usages: %sintrosong with a file attached to your message. \n", prefix) +
                String.format("%sintrosong with a youtube link. \n", prefix) +
                String.format("%sintrosong with link or attachment and a number between 0 and 100 to represent volume of the intro song wanted (overrides and reverts the server default). \n", prefix) +
                String.format("Aliases are: '%s'", String.join(",", getAliases()));
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("intro", "setintro");
    }
}
