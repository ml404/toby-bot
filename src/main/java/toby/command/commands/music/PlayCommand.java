package toby.command.commands.music;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.PlayerManager;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;

import static toby.helpers.MusicPlayerHelper.playUserIntro;


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
        String link = String.join(" ", ctx.getArgs());
        if (link.equals("intro")) {
            Guild guild = ctx.getGuild();
            int currentVolume = PlayerManager.getInstance().getMusicManager(guild).getAudioPlayer().getVolume();
            playUserIntro(requestingUserDto, guild, channel, deleteDelay);
            channel.sendMessageFormat("Changing volume back to '%s' \uD83D\uDD0A", currentVolume).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            PlayerManager.getInstance().getMusicManager(guild).getAudioPlayer().setVolume(currentVolume);
            return;
        }
        if (link.contains("youtube") && !isUrl(link)) {
            link = "ytsearch:" + link;
        }

        PlayerManager.getInstance().loadAndPlay(channel, link, deleteDelay);
    }


    @Override
    public String getName() {
        return "play";
    }

    @Override
    public String getHelp(String prefix) {
        return "Plays a song\n" +
                String.format("Usage: `%splay <youtube link>`\n", prefix) +
                String.format("Aliases are: '%s'", String.join(",", getAliases()));
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("sing");
    }

    private boolean isUrl(String url) {
        try {
            new URI(url);
            return true;
        } catch (URISyntaxException e) {
            return false;
        }
    }
}