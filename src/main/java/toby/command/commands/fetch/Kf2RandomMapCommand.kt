package toby.command.commands.fetch;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.command.commands.misc.RandomCommand;
import toby.helpers.Cache;
import toby.helpers.WikiFetcher;
import toby.jpa.dto.UserDto;

import java.io.IOException;
import java.util.List;

public class Kf2RandomMapCommand implements IFetchCommand {

    private static final String kf2WebUrl = "https://wiki.killingfloor2.com/index.php?title=Maps_(Killing_Floor_2)";
    private static final String cacheName = "kf2Maps";
    private static final String className = "mw-parser-output";
    private final Cache cache;

    public Kf2RandomMapCommand(Cache loadingCache) {
        this.cache = loadingCache;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        final SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        try {
            WikiFetcher wikiFetcher = new WikiFetcher(cache);
            List<String> kf2Maps = wikiFetcher.fetchFromWiki(cacheName, kf2WebUrl, className, "b");
            event.getHook().sendMessage(RandomCommand.getRandomElement(kf2Maps)).queue(message -> ICommand.deleteAfter(event.getHook(), deleteDelay));
        } catch (IOException ignored) {
            event.getHook().sendMessage("Huh, the website I pull data from must have returned something unexpected.").setEphemeral(true).queue(message -> ICommand.deleteAfter(event.getHook(), deleteDelay));
        }
    }

    @Override
    public String getName() {
        return "kf2";
    }

    @Override
    public String getDescription() {
        return "return a random kf2 map";
    }

}
