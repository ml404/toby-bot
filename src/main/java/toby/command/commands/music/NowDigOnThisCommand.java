package toby.command.commands.music;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.helpers.URLHelper;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;

import java.util.List;

import static toby.helpers.MusicPlayerHelper.adjustTrackPlayingTimes;


public class NowDigOnThisCommand implements IMusicCommand {

    private final String LINK = "link";
    private final String START_POSITION = "start";

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        final SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        if (requestingUserDto.hasDigPermission()) {
            String link = event.getOption(LINK).getAsString();
            if (link== null) {
                event.replyFormat("Correct usage is `%snowdigonthis <youtube link>`", "/").queue(message -> ICommand.deleteAfter(message, deleteDelay));
                return;
            }
            if (IMusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return;
            if (link.contains("youtube") && !URLHelper.isValidURL(link)) link = "ytsearch:" + link;
            Long startPosition = adjustTrackPlayingTimes(event.getOption(START_POSITION).getAsLong());
            PlayerManager.getInstance().loadAndPlay(event, link, false, deleteDelay, startPosition);
        } else
            sendErrorMessage(event, deleteDelay);
    }


    public static void sendDeniedStoppableMessage(SlashCommandInteractionEvent event, GuildMusicManager musicManager, Integer deleteDelay) {
        if (musicManager.getScheduler().getQueue().size() > 1) {
            event.reply("Our daddy taught us not to be ashamed of our playlists").queue(message -> ICommand.deleteAfter(message, deleteDelay));
        } else {
            long duration = musicManager.getAudioPlayer().getPlayingTrack().getDuration();
            String songDuration = QueueCommand.formatTime(duration);
            event.replyFormat("HEY FREAK-SHOW! YOU AIN’T GOIN’ NOWHERE. I GOTCHA’ FOR %s, %s OF PLAYTIME!", songDuration, songDuration).queue(message -> ICommand.deleteAfter(message, deleteDelay));
        }
    }

    @Override
    public String getName() {
        return "nowdigonthis";
    }

    @Override
    public String getDescription() {
        return "Plays a song which cannot be skipped";
    }

    @Override
    public String getErrorMessage() {
        return "I'm gonna put some dirt in your eye %s";
    }

    @Override
    public void sendErrorMessage(SlashCommandInteractionEvent event, Integer deleteDelay) {
        event.replyFormat(getErrorMessage(), event.getMember().getNickname()).queue(message -> ICommand.deleteAfter(message, deleteDelay));
    }

    @Override
    public List<OptionData> getOptionData() {
        OptionData linkArg = new OptionData(OptionType.STRING, LINK, "Link to play that cannot be stopped unless requested by a super user", true);
        OptionData startPositionArg = new OptionData(OptionType.INTEGER, START_POSITION, "Start position of the track in seconds");
        return List.of(linkArg, startPositionArg);
    }
}