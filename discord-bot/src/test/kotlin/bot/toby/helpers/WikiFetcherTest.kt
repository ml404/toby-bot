package bot.toby.helpers

import bot.toby.helpers.*
import org.junit.jupiter.api.*

/**
 * WikiFetcher Tester.
 *
 * @author <Matthew Layton>
 * @version 1.0
 * @since <pre>May 8, 2021</pre>
</Matthew> */
class WikiFetcherTest {
    @BeforeEach
    fun before() {
    }

    @AfterEach
    fun after() {
    }


    @Test
    @Disabled
    @Throws(Exception::class)
    fun testKf2WikiFetcher() {
        val kf2WebUrl = "https://wiki.killingfloor2.com/index.php?title=Maps_(Killing_Floor_2)"
        val cacheName = "kf2Maps"
        val className = "mw-parser-output"

        val cache = Cache(86400, 3600, 2)
        val wikiFetcher = WikiFetcher(cache)
        val mapStrings = wikiFetcher.fetchFromWiki(cacheName, kf2WebUrl, className, "b")

        Assertions.assertNotNull(mapStrings)
        Assertions.assertEquals(mapStrings.size, 35)
    }


    @Test
    @Disabled
    @Throws(Exception::class)
    fun testDeadByDaylightKillerFetcher() {
        val dbdWebUrl = "https://deadbydaylight.fandom.com/wiki/Killers"
        val cacheName = "dbdKillers"
        val className = "mw-content-ltr"
        val cssQuery = "<div style=\"color: #fff;\">"

        val cache = Cache(86400, 3600, 2)
        val wikiFetcher = WikiFetcher(cache)
        val mapStrings = wikiFetcher.fetchFromWiki(cacheName, dbdWebUrl, className, cssQuery)

        Assertions.assertNotNull(mapStrings)
        Assertions.assertEquals(mapStrings.size, 26)
    }
}
