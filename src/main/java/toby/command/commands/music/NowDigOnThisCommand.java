package toby.command.commands.music;

import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.TextChannel;
import org.jetbrains.annotations.Nullable;
import toby.command.CommandContext;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;

public class NowDigOnThisCommand implements IMusicCommand {

    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto) {
        final TextChannel channel = ctx.getChannel();
        if (requestingUserDto.hasDigPermission()) {
            Member member = doChannelValidation(ctx, prefix, channel);
            if (member == null) return;

            String link = String.join(" ", ctx.getArgs());
            if (link.contains("youtube") && !isUrl(link)) link = "ytsearch:" + link;

            PlayerManager.getInstance().loadAndPlay(channel, link, false);
        } else
            sendErrorMessage(ctx, channel);
    }

    @Nullable
    private Member doChannelValidation(CommandContext ctx, String prefix, TextChannel channel) {
        if (ctx.getArgs().isEmpty()) {
            channel.sendMessageFormat("Correct usage is `%snowdigonthis <youtube link>`", prefix).queue();
            return null;
        }

        final Member self = ctx.getSelfMember();
        final GuildVoiceState selfVoiceState = self.getVoiceState();

        if (!selfVoiceState.inVoiceChannel()) {
            channel.sendMessage("I need to be in a voice channel for this to work").queue();
            return null;
        }

        final Member member = ctx.getMember();
        final GuildVoiceState memberVoiceState = member.getVoiceState();

        if (!memberVoiceState.inVoiceChannel()) {
            channel.sendMessage("You need to be in a voice channel for this command to work").queue();
            return null;
        }

        if (!memberVoiceState.getChannel().equals(selfVoiceState.getChannel())) {
            channel.sendMessage("You need to be in the same voice channel as me for this to work").queue();
            return null;
        }
        return member;
    }

    public static void sendDeniedStoppableMessage(TextChannel channel, GuildMusicManager musicManager) {
        if (musicManager.getScheduler().getQueue().size() > 1) {
            channel.sendMessage("Our daddy taught us not to be ashamed of our playlists").queue();
        } else {
            long duration = musicManager.getAudioPlayer().getPlayingTrack().getDuration();
            String songDuration = QueueCommand.formatTime(duration);
            channel.sendMessage(String.format("HEY FREAK-SHOW! YOU AIN’T GOIN’ NOWHERE. I GOTCHA’ FOR %s, %s OF PLAYTIME!", songDuration, songDuration)).queue();
        }
    }

    @Override
    public String getName() {
        return "nowdigonthis";
    }

    @Override
    public String getHelp(String prefix) {
        return "Plays a song\n" +
                String.format("Usage: `%snowdigonthis <youtube link>` \n", prefix) +
                String.format("Aliases are: %s", String.join(",", getAliases()));
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("ndot", "dig");
    }

    @Override
    public String getErrorMessage() {
        return "I'm gonna put some dirt in your eye %s";
    }

    @Override
    public void sendErrorMessage(CommandContext ctx, TextChannel channel) {
        channel.sendMessageFormat(getErrorMessage(), ctx.getEvent().getMember().getNickname()).queue();
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