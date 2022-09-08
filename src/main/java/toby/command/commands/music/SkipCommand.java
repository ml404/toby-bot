package toby.command.commands.music;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;

import java.util.List;

import static toby.command.commands.music.NowDigOnThisCommand.sendDeniedStoppableMessage;
import static toby.helpers.MusicPlayerHelper.nowPlaying;


public class SkipCommand implements IMusicCommand {

    private final String SKIP = "skip";

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        final SlashCommandInteractionEvent event = ctx.getEvent();
        if (IMusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return;
        final GuildMusicManager musicManager = PlayerManager.getInstance().getMusicManager(event.getGuild());
        final AudioPlayer audioPlayer = musicManager.getAudioPlayer();

        if (audioPlayer.getPlayingTrack() == null) {
            event.reply("There is no track playing currently").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return;
        }
        int skipValue = event.getOption(SKIP).getAsInt();
        int tracksToSkip = skipValue !=0 ? skipValue : 1;

        if (tracksToSkip < 0) {
            event.reply("You're not too bright, but thanks for trying").setEphemeral(true).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return;
        }

        if (PlayerManager.getInstance().isCurrentlyStoppable() || requestingUserDto.isSuperUser()) {
            for (int j = 0; j < tracksToSkip; j++) {
                musicManager.getScheduler().nextTrack();
            }
            musicManager.getScheduler().setLooping(false);
            event.replyFormat("Skipped %d track(s)", tracksToSkip).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            nowPlaying(event, musicManager.getAudioPlayer().getPlayingTrack(), deleteDelay);
        } else {
            sendDeniedStoppableMessage(event, musicManager, deleteDelay);
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

