package toby.command.commands.music;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;

import java.util.List;
import java.util.Optional;

import static toby.command.ICommand.getConsumer;
import static toby.helpers.MusicPlayerHelper.nowPlaying;


public class SkipCommand implements IMusicCommand {

    private final String SKIP = "skip";

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        handleMusicCommand(ctx, PlayerManager.getInstance(), requestingUserDto, deleteDelay);
    }

    @Override
    public void handleMusicCommand(CommandContext ctx, PlayerManager instance, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        final SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        if (IMusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return;
        GuildMusicManager musicManager = instance.getMusicManager(ctx.getGuild());
        final AudioPlayer audioPlayer = musicManager.getAudioPlayer();

        if (audioPlayer.getPlayingTrack() == null) {
            event.getHook().sendMessage("There is no track playing currently").queue(getConsumer(deleteDelay));
            return;
        }
        int tracksToSkip = Optional.ofNullable(event.getOption(SKIP)).map(OptionMapping::getAsInt).orElse(1);

        if (tracksToSkip < 0) {
            event.getHook().sendMessage("You're not too bright, but thanks for trying").setEphemeral(true).queue(getConsumer(deleteDelay));
            return;
        }

        if (PlayerManager.getInstance().isCurrentlyStoppable() || requestingUserDto.isSuperUser()) {
            for (int j = 0; j < tracksToSkip; j++) {
                musicManager.getScheduler().nextTrack();
            }
            musicManager.getScheduler().setLooping(false);
            event.getHook().sendMessageFormat("Skipped %d track(s)", tracksToSkip).queue(getConsumer(deleteDelay));
            AudioTrack playingTrack = musicManager.getAudioPlayer().getPlayingTrack();
            nowPlaying(event, playingTrack, deleteDelay, (int) playingTrack.getUserData());
        } else {
            IMusicCommand.sendDeniedStoppableMessage(event, musicManager, deleteDelay);
        }
    }

    @Override
    public String getName() {
        return "skip";
    }

    @Override
    public String getDescription() {
        return "skip X number of tracks. Skips 1 by default";
    }

    @Override
    public List<OptionData> getOptionData() {
        return List.of(new OptionData(OptionType.INTEGER, SKIP, "Number of tracks to skip"));
    }
}

