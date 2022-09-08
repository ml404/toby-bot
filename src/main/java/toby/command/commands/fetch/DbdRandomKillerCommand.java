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

public class DbdRandomKillerCommand implements IFetchCommand {

    private static final String dbdWebUrl = "https://deadbydaylight.fandom.com/wiki/Killers";
    public static final String cacheName = "dbdKillers";
    public static final String className = "mw-content-ltr";
    public static final String cssQuery = "div";
    private final Cache cache;

    public DbdRandomKillerCommand(Cache loadingCache) {
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
            List<String> dbdKillers = wikiFetcher.fetchFromWiki(cacheName, dbdWebUrl, className, cssQuery);
            event.replyFormat(RandomCommand.getRandomElement(dbdKillers)).queue(message -> ICommand.deleteAfter(message, deleteDelay));

        } catch (IOException ignored) {
            event.replyFormat("Huh, the website I pull data from must have returned something unexpected.").queue(message -> ICommand.deleteAfter(message, deleteDelay));
        }
    }

    @Override
    public String getName() {
        return "dbd";
    }

    @Override
    public String getDescription() {
        return "return a random dead by daylight killer";
    }

}
