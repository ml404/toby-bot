package toby.command.commands.music;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.MusicDto;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.PlayerManager;

import java.util.List;
import java.util.stream.Collectors;

import static toby.helpers.MusicPlayerHelper.*;


public class PlayCommand implements IMusicCommand {

    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getMessage(), deleteDelay);
        final TextChannel channel = ctx.getChannel();
        if (!requestingUserDto.hasMusicPermission()) {
            sendErrorMessage(ctx, channel, deleteDelay);
            return;
        }
        if (ctx.getArgs().isEmpty()) {
            channel.sendMessage("Correct usage is `!play <youtube link>`").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return;
        }
        if (IMusicCommand.isInvalidChannelStateForCommand(ctx, channel, deleteDelay)) return;
        List<String> nonAdjustmentArgs = ctx.getArgs()
                .stream()
                .filter(s -> !s.startsWith(MusicDto.Adjustment.START.name()) || !s.startsWith(MusicDto.Adjustment.END.name()))
                .collect(Collectors.toList());
        String link = String.join(" ", nonAdjustmentArgs);
        PlayerManager instance = PlayerManager.getInstance();
        Guild guild = ctx.getGuild();
        int currentVolume = instance.getMusicManager(guild).getAudioPlayer().getVolume();
        instance.setPreviousVolume(currentVolume);
        Long startPosition = adjustTrackPlayingTimes(ctx.getArgs(), channel, deleteDelay);

        if (link.equals("intro")) {
            playUserIntro(requestingUserDto, guild, channel, deleteDelay, startPosition);
            return;
        }
        if (link.contains("youtube") && !isUrl(link)) {
            link = "ytsearch:" + link;
        }
        instance.loadAndPlay(channel, link, true, deleteDelay, startPosition);
    }


    @Override
    public String getName() {
        return "play";
    }

    @Override
    public String getHelp(String prefix) {
        return "Plays a song\n" +
                String.format("Usage: `%splay <youtube link>`\n", prefix) +
                String.format("Optionally you may specify a start time for the link in seconds by using: `%splay <youtube link> start=<time in seconds>`\n", prefix) +
                String.format("Aliases are: '%s'", String.join(",", getAliases()));
    }

    @Override
    public List<String> getAliases() {
        return List.of("sing,shout,rap");
    }
}