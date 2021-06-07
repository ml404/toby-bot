package toby.command.commands.fetch;

import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.command.commands.misc.RandomCommand;
import toby.helpers.Cache;
import toby.helpers.WikiFetcher;
import toby.jpa.dto.UserDto;

import java.io.IOException;
import java.util.Arrays;
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
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getMessage(), deleteDelay);
        final TextChannel channel = ctx.getChannel();
        try {
            WikiFetcher wikiFetcher = new WikiFetcher(cache);
            List<String> dbdKillers = wikiFetcher.fetchFromWiki(cacheName, dbdWebUrl, className, cssQuery);
            channel.sendMessage(RandomCommand.getRandomElement(dbdKillers)).queue(message -> ICommand.deleteAfter(message, deleteDelay));

        } catch (IOException ignored) {
            channel.sendMessage("Huh, the website I pull data from must have returned something unexpected.").queue(message -> ICommand.deleteAfter(message, deleteDelay));
        }
    }

    @Override
    public String getName() {
        return "dbd";
    }

    @Override
    public String getHelp(String prefix) {
        return "return a random dead by daylight killer \n" +
                String.format("Usage: %sdbd", prefix) +
                String.format("Aliases are: '%s'", String.join(",", getAliases()));
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("dbd", "killer");
    }
}
