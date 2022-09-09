package toby.command.commands.music;


import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.emote.Emotes;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.PlayerManager;

import java.util.List;


public class SetVolumeCommand implements IMusicCommand {

    private final String VOLUME = "volume";

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        ICommand.deleteAfter(event.getHook(), deleteDelay);

        if (!requestingUserDto.hasMusicPermission()) {
            sendErrorMessage(event, deleteDelay);
            return;
        }

        if (IMusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return;
        final Member member = ctx.getMember();
        setNewVolume(event, member, deleteDelay);
    }


    private void setNewVolume(SlashCommandInteractionEvent event, Member member, Integer deleteDelay) {
        Guild guild = event.getGuild();
        int volumeArg = event.getOption(VOLUME).getAsInt();
        if (volumeArg > 0) {
            if (PlayerManager.getInstance().isCurrentlyStoppable() || member.hasPermission(Permission.KICK_MEMBERS)) {
                AudioPlayer audioPlayer = PlayerManager.getInstance().getMusicManager(guild).getAudioPlayer();
                if (volumeArg > 100) {
                    event.getHook().sendMessage(getDescription()).setEphemeral(true).queue(message -> ICommand.deleteAfter(message, deleteDelay));
                    return;
                }
                int oldVolume = audioPlayer.getVolume();
                if (volumeArg == oldVolume) {
                    event.replyFormat("New volume and old volume are the same value, somebody shoot %s", member.getEffectiveName()).setEphemeral(true).queue(message -> ICommand.deleteAfter(message, deleteDelay));
                    return;
                }
                audioPlayer.setVolume(volumeArg);
                event.replyFormat("Changing volume from '%s' to '%s' \uD83D\uDD0A", oldVolume, volumeArg).setEphemeral(true).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            } else {
                event.replyFormat("You aren't allowed to change the volume kid %s", Emotes.TOBY).setEphemeral(true).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            }
        } else event.getHook().sendMessage(getDescription()).setEphemeral(true).queue(message -> ICommand.deleteAfter(message, deleteDelay));
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
