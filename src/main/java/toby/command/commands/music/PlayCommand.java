package toby.command.commands.music;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.controller.ConsumeWebService;
import toby.jpa.dto.MusicDto;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.PlayerManager;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;

public class PlayCommand implements IMusicCommand {

    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getMessage(), deleteDelay);
        final TextChannel channel = ctx.getChannel();

        if (!requestingUserDto.hasMusicPermission()) {
            sendErrorMessage(ctx, channel, deleteDelay);
            return;
        }

        if (doMethodAndChannelValidation(ctx, channel, deleteDelay)) return;

        String link = String.join(" ", ctx.getArgs());

        if(link.equals("intro")){
            playUserIntro(requestingUserDto, ctx.getGuild());
            return;
        }

        if (link.contains("youtube") && !isUrl(link)) {
            link = "ytsearch:" + link;
        }

        PlayerManager.getInstance().loadAndPlay(channel, link, deleteDelay);
    }

    private boolean doMethodAndChannelValidation(CommandContext ctx, TextChannel channel, Integer deleteDelay) {
        if (ctx.getArgs().isEmpty()) {
            channel.sendMessage("Correct usage is `!play <youtube link>`").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return true;
        }

        final Member self = ctx.getSelfMember();
        final GuildVoiceState selfVoiceState = self.getVoiceState();

        if (!selfVoiceState.inVoiceChannel()) {
            channel.sendMessage("I need to be in a voice channel for this to work").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return true;
        }

        final Member member = ctx.getMember();
        final GuildVoiceState memberVoiceState = member.getVoiceState();

        if (!memberVoiceState.inVoiceChannel()) {
            channel.sendMessage("You need to be in a voice channel for this command to work").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return true;
        }

        if (!memberVoiceState.getChannel().equals(selfVoiceState.getChannel())) {
            channel.sendMessage("You need to be in the same voice channel as me for this to work").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return true;
        }
        return false;
    }

    private void playUserIntro(UserDto dbUser, Guild guild) {
        MusicDto musicDto = dbUser.getMusicDto();
        if (musicDto != null && musicDto.getFileName() != null) {
            PlayerManager.getInstance().loadAndPlay(guild.getSystemChannel(),
                    String.format(ConsumeWebService.getWebUrl() + "/music?id=%s", musicDto.getId()),
                    0);
        } else if (musicDto != null) {
            PlayerManager.getInstance().loadAndPlay(guild.getSystemChannel(), Arrays.toString(dbUser.getMusicDto().getMusicBlob()),
                    0);
        }
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