package toby.command.commands.music;


import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;
import toby.lavaplayer.TrackScheduler;

public class LoopCommand implements ICommand {
    @SuppressWarnings("ConstantConditions")
    @Override
    public void handle(CommandContext ctx) {
        final TextChannel channel = ctx.getChannel();

        final Member self = ctx.getSelfMember();
        final GuildVoiceState selfVoiceState = self.getVoiceState();

        if (!selfVoiceState.inVoiceChannel()) {
            channel.sendMessage("I need to be in a voice channel for this to work").queue();
            return;
        }

        final Member member = ctx.getMember();
        final GuildVoiceState memberVoiceState = member.getVoiceState();

        if (!memberVoiceState.inVoiceChannel()) {
            channel.sendMessage("You need to be in a voice channel for this command to work").queue();
            return;
        }

        if (!memberVoiceState.getChannel().equals(selfVoiceState.getChannel())) {
            channel.sendMessage("You need to be in the same voice channel as me for this to work").queue();
            return;
        }

        GuildMusicManager musicManager = PlayerManager.getInstance().getMusicManager(ctx.getGuild());
        TrackScheduler scheduler = musicManager.scheduler;
        boolean newIsRepeating = !scheduler.isLooping();
        scheduler.setLooping(newIsRepeating);

        channel.sendMessageFormat("The Player has been set to **%s**", newIsRepeating ? "looping" : "not looping").queue();
    }

    @Override
    public String getName() {
        return "loop";
    }

    @Override
    public String getHelp() {
        return "Loop the current song\n" +
                "Usage: `!loop`";
    }
}
