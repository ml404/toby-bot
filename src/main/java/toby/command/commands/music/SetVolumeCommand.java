package toby.command.commands.music;


import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.emote.Emotes;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;

import java.util.List;
import java.util.Optional;

import static toby.command.ICommand.getConsumer;


public class SetVolumeCommand implements IMusicCommand {

    private final String VOLUME = "volume";


    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        handleMusicCommand(ctx, PlayerManager.getInstance(), requestingUserDto, deleteDelay);
    }

    @Override
    public void handleMusicCommand(CommandContext ctx, PlayerManager instance, UserDto requestingUserDto, Integer deleteDelay) {
        SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        ICommand.deleteAfter(event.getHook(), deleteDelay);

        if (!requestingUserDto.hasMusicPermission()) {
            sendErrorMessage(event, deleteDelay);
            return;
        }

        if (IMusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return;

        final Member member = ctx.getMember();
        setNewVolume(event, instance, member, requestingUserDto, deleteDelay);
    }

    private void setNewVolume(SlashCommandInteractionEvent event, PlayerManager instance, Member member, UserDto requestingUserDto, Integer deleteDelay) {
        int volumeArg = Optional.ofNullable(event.getOption(VOLUME)).map(OptionMapping::getAsInt).orElse(0);
        InteractionHook hook = event.getHook();
        GuildMusicManager musicManager = instance.getMusicManager(event.getGuild());
        if (volumeArg > 0) {
            if (instance.isCurrentlyStoppable() || requestingUserDto.isSuperUser()) {
                AudioPlayer audioPlayer = musicManager.getAudioPlayer();
                if (volumeArg > 100) {
                    hook.sendMessage(getDescription()).setEphemeral(true).queue(getConsumer(deleteDelay));
                    return;
                }
                int oldVolume = audioPlayer.getVolume();
                if (volumeArg == oldVolume) {
                    hook.sendMessageFormat("New volume and old volume are the same value, somebody shoot %s", member.getEffectiveName()).setEphemeral(true).queue(getConsumer(deleteDelay));
                    return;
                }
                audioPlayer.setVolume(volumeArg);
                hook.sendMessageFormat("Changing volume from '%s' to '%s' \uD83D\uDD0A", oldVolume, volumeArg).setEphemeral(true).queue(getConsumer(deleteDelay));
            } else {
                sendErrorMessage(event, deleteDelay);
            }
        } else hook.sendMessage(getDescription()).setEphemeral(true).queue(getConsumer(deleteDelay));
    }

    @Override
    public void sendErrorMessage(SlashCommandInteractionEvent event, Integer deleteDelay) {
        event.getHook().sendMessageFormat("You aren't allowed to change the volume kid %s", event.getGuild().getJDA().getEmojiById(Emotes.TOBY)).setEphemeral(true).queue(getConsumer(deleteDelay));
    }


    @Override
    public String getName() {
        return "setvolume";
    }

    @Override
    public String getDescription() {
        return "Set the volume of the audio player for the server to a percent value (between 1 and 100)";

    }

    @Override
    public List<OptionData> getOptionData() {
        return List.of(new OptionData(OptionType.INTEGER, VOLUME, "Volume value between 1-100 to set the audio to", true));
    }
}
