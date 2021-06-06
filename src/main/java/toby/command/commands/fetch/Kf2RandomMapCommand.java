package toby.command.commands.fetch;

import net.dv8tion.jda.api.entities.TextChannel;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.command.commands.misc.RandomCommand;
import toby.helpers.Cache;
import toby.jpa.dto.UserDto;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class Kf2RandomMapCommand implements IFetchCommand {

    private static final String kf2WebUrl = "https://wiki.killingfloor2.com/index.php?title=Maps_(Killing_Floor_2)";
    private final Cache cache;

    public Kf2RandomMapCommand(Cache loadingCache) {
        this.cache = loadingCache;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getMessage(), deleteDelay);
        final TextChannel channel = ctx.getChannel();
        try {
            List<String> kf2Maps = (List<String>) cache.get("kf2Maps");
            if(kf2Maps == null) kf2Maps = getMapStrings();
            channel.sendMessage(RandomCommand.getRandomElement(kf2Maps)).queue(message -> ICommand.deleteAfter(message, deleteDelay));

        } catch (IOException ignored) {
            channel.sendMessage("Huh, the website I pull data from must have returned something unexpected.").queue(message -> ICommand.deleteAfter(message, deleteDelay));
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> getMapStrings() throws IOException {
        Element mapElement = fetchKf2Maps();
        List<String> mapStrings = mapElement.select("b").eachText();
        cache.put("kf2Maps", mapStrings);
        return mapStrings;
    }

    private Element fetchKf2Maps() throws IOException {
        Document doc = Jsoup.connect(kf2WebUrl).get();
        Elements mapElements = doc.getElementsByClass("mw-parser-output");
        return mapElements.get(0);
    }

    @Override
    public String getName() {
        return "kf2";
    }

    @Override
    public String getHelp(String prefix) {
        return "return a random kf2 map \n" +
                String.format("Usage: %skf2", prefix);
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("kf2map", "kfmap", "kfrand");
    }
}
