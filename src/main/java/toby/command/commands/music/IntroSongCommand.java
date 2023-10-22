package toby.command.commands.music;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Mentions;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import toby.command.CommandContext;
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
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static toby.command.ICommand.deleteAfter;
import static toby.command.ICommand.getConsumer;
import static toby.helpers.FileUtils.readInputStreamToByteArray;
import static toby.helpers.UserDtoHelper.calculateUserDto;

public class IntroSongCommand implements IMusicCommand {
    private static final String VOLUME = "volume";
    private final IUserService userService;
    private final IMusicFileService musicFileService;
    private final IConfigService configService;
    private final String USERS = "users";
    private final String LINK = "link";
    private final String ATTACHMENT = "attachment";

    public IntroSongCommand(IUserService userService, IMusicFileService musicFileService, IConfigService configService) {
        this.userService = userService;
        this.musicFileService = musicFileService;
        this.configService = configService;
    }

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        final SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        deleteAfter(event.getHook(), deleteDelay);
        String volumePropertyName = ConfigDto.Configurations.VOLUME.getConfigValue();
        int defaultVolume = Integer.parseInt(configService.getConfigByName(volumePropertyName, event.getGuild().getId()).getValue());
        Optional<Integer> volumeOptional = Optional.ofNullable(event.getOption(VOLUME)).map(OptionMapping::getAsInt);
        int introVolume = volumeOptional.orElse(defaultVolume);
        if (introVolume < 1) introVolume = 1;
        if (introVolume > 100) introVolume = 100;
        List<Member> mentionedMembers = Optional.ofNullable(event.getOption(USERS)).map(OptionMapping::getMentions).map(Mentions::getMembers).orElse(Collections.emptyList());
        if (!requestingUserDto.isSuperUser() && !mentionedMembers.isEmpty()) {
            sendErrorMessage(event, deleteDelay);
            return;
        }
        Optional<Message.Attachment> optionalAttachment = Optional.ofNullable(event.getOption(ATTACHMENT)).map(OptionMapping::getAsAttachment);
        String link = Optional.ofNullable(event.getOption(LINK)).map(OptionMapping::getAsString).orElse("");

        if (optionalAttachment.isPresent() && URLHelper.isValidURL(link)) {
            event.getHook().sendMessage(getDescription()).queue(getConsumer(deleteDelay));
        } else if (!link.isEmpty()) {
            setIntroViaUrl(event, requestingUserDto, deleteDelay, URLHelper.fromUrlString(link), introVolume);
        } else {
            if(optionalAttachment.isPresent()) setIntroViaDiscordAttachment(event, requestingUserDto, deleteDelay, optionalAttachment.get(), introVolume);
        }
    }

    private void setIntroViaDiscordAttachment(SlashCommandInteractionEvent event, UserDto requestingUserDto, Integer deleteDelay, Message.Attachment attachment, int introVolume) {
        if (!Objects.equals(attachment.getFileExtension(), "mp3")) {
            event.getHook().sendMessage("Please use mp3 files only").queue(getConsumer(deleteDelay));
            return;
        } else if (attachment.getSize() > 300000) {
            event.getHook().sendMessage("Please keep the file size under 300kb").queue(getConsumer(deleteDelay));
        }

        InputStream inputStream = null;
        try {
            inputStream = attachment.getProxy().download().get();
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }
        List<Member> mentionedMembers = Optional.ofNullable(event.getOption(USERS)).map(OptionMapping::getMentions).map(Mentions::getMembers).orElse(Collections.emptyList());
        if (mentionedMembers.size() > 0 && requestingUserDto.isSuperUser()) {
            InputStream finalInputStream = inputStream;
            mentionedMembers.forEach(member -> {
                UserDto userDto = calculateUserDto(member.getGuild().getIdLong(), member.getIdLong(), member.isOwner(), userService, introVolume);
                persistMusicFile(event, userDto, deleteDelay, attachment.getFileName(), introVolume, finalInputStream, member.getEffectiveName());
            });
        } else
            persistMusicFile(event, requestingUserDto, deleteDelay, attachment.getFileName(), introVolume, inputStream, event.getUser().getName());
    }

    private void setIntroViaUrl(SlashCommandInteractionEvent event, UserDto requestingUserDto, Integer deleteDelay, Optional<URI> optionalURI, int introVolume) {
        List<Member> mentionedMembers = Optional.ofNullable(event.getOption(USERS)).map(OptionMapping::getMentions).map(Mentions::getMembers).orElse(Collections.emptyList());
        String urlString = optionalURI.map(URI::toString).orElse("");

        if (mentionedMembers.size() > 0 && requestingUserDto.isSuperUser()) {
            mentionedMembers.forEach(member -> {
                UserDto userDto = calculateUserDto(member.getGuild().getIdLong(), member.getIdLong(), member.isOwner(), userService, introVolume);
                persistMusicUrl(event, userDto, deleteDelay, urlString, urlString, member.getEffectiveName(), introVolume);
            });
        } else
            persistMusicUrl(event, requestingUserDto, deleteDelay, urlString, urlString, event.getUser().getName(), introVolume);
    }


    private void persistMusicFile(SlashCommandInteractionEvent event, UserDto targetDto, Integer deleteDelay, String filename, int introVolume, InputStream inputStream, String memberName) {
        byte[] fileContents;
        try {
            fileContents = readInputStreamToByteArray(inputStream);
        } catch (ExecutionException | InterruptedException | IOException e) {
            event.getHook().sendMessageFormat("Unable to read file '%s'", filename).setEphemeral(true).queue(getConsumer(deleteDelay));
            return;
        }
        MusicDto musicFileDto = targetDto.getMusicDto();
        if (musicFileDto == null) {
            MusicDto musicDto = new MusicDto(targetDto.getDiscordId(), targetDto.getGuildId(), filename, introVolume, fileContents);
            musicFileService.createNewMusicFile(musicDto);
            targetDto.setMusicDto(musicDto);
            userService.updateUser(targetDto);
            event.getHook().sendMessageFormat("Successfully set %s's intro song to '%s' with volume '%d'", memberName, musicDto.getFileName(), musicDto.getIntroVolume()).setEphemeral(true).queue(getConsumer(deleteDelay));
            return;
        }
        musicFileDto.setFileName(filename);
        musicFileDto.setIntroVolume(introVolume);
        musicFileDto.setMusicBlob(fileContents);
        musicFileService.updateMusicFile(musicFileDto);
        event.getHook().sendMessageFormat("Successfully updated %s's intro song to '%s' with volume '%d'", memberName, filename, introVolume).setEphemeral(true).queue(getConsumer(deleteDelay));
    }

    private void persistMusicUrl(SlashCommandInteractionEvent event, UserDto targetDto, Integer deleteDelay, String filename, String url, String memberName, int introVolume) {
        MusicDto musicFileDto = targetDto.getMusicDto();
        byte[] urlBytes = url.getBytes();
        if (musicFileDto == null) {
            MusicDto musicDto = new MusicDto(targetDto.getDiscordId(), targetDto.getGuildId(), filename, introVolume, urlBytes);
            musicFileService.createNewMusicFile(musicDto);
            targetDto.setMusicDto(musicDto);
            userService.updateUser(targetDto);
            event.getHook().sendMessageFormat("Successfully set %s's intro song to '%s' with volume '%d'", memberName, musicDto.getFileName(), musicDto.getIntroVolume()).setEphemeral(true).queue(getConsumer(deleteDelay));
            return;
        }
        musicFileDto.setFileName(filename);
        musicFileDto.setIntroVolume(introVolume);
        musicFileDto.setMusicBlob(urlBytes);
        musicFileService.updateMusicFile(musicFileDto);
        event.getHook().sendMessageFormat("Successfully updated %s's intro song to '%s' with volume '%d'", memberName, filename, introVolume).setEphemeral(true).queue(getConsumer(deleteDelay));
    }


    @Override
    public String getName() {
        return "introsong";
    }

    @Override
    public String getDescription() {
        return "Upload a **MP3** file to play when you join a voice channel. Can use youtube links instead.";
    }

    @Override
    public List<OptionData> getOptionData() {
        OptionData users = new OptionData(OptionType.STRING, USERS, "User whose intro to change");
        OptionData link = new OptionData(OptionType.STRING, LINK, "Link to set as your discord intro");
        OptionData attachment = new OptionData(OptionType.ATTACHMENT, ATTACHMENT, "Attachment (file) to set as your discord intro");
        OptionData volume = new OptionData(OptionType.INTEGER, VOLUME, "volume to set your intro to");
        return List.of(users, link, attachment, volume);
    }
}
