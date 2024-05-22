package helpers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import toby.helpers.Cache;
import toby.helpers.WikiFetcher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * WikiFetcher Tester.
 *
 * @author <Matthew Layton>
 * @version 1.0
 * @since <pre>May 8, 2021</pre>
 */
public class WikiFetcherTest {

    @BeforeEach
    public void before() {

    }

    @AfterEach
    public void after() {
    }


    @Test
    @Disabled
    public void testKf2WikiFetcher() throws Exception {

        final String kf2WebUrl = "https://wiki.killingfloor2.com/index.php?title=Maps_(Killing_Floor_2)";
        final String cacheName = "kf2Maps";
        final String className = "mw-parser-output";

        var cache = new Cache(86400, 3600, 2);
        WikiFetcher wikiFetcher = new WikiFetcher(cache);
        List<String> mapStrings = wikiFetcher.fetchFromWiki(cacheName, kf2WebUrl, className, "b");

        assertNotNull(mapStrings);
        assertEquals(mapStrings.size(), 35);

    }


    @Test
    @Disabled
    public void testDeadByDaylightKillerFetcher() throws Exception {
        final String dbdWebUrl = "https://deadbydaylight.fandom.com/wiki/Killers";
        final String cacheName = "dbdKillers";
        final String className = "mw-content-ltr";
        final String cssQuery = "<div style=\"color: #fff;\">";

        var cache = new Cache(86400, 3600, 2);
        WikiFetcher wikiFetcher = new WikiFetcher(cache);
        List<String> mapStrings = wikiFetcher.fetchFromWiki(cacheName, dbdWebUrl, className, cssQuery);

        assertNotNull(mapStrings);
        assertEquals(mapStrings.size(), 26);
    }


}
