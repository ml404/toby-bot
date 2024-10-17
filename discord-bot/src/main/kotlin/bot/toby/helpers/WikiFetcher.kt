package bot.toby.helpers

import common.helpers.Cache
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException

class WikiFetcher(private val cache: Cache) {

    @Throws(IOException::class)
    fun fetchFromWiki(cacheToRetrieve: String, webUrl: String, className: String, cssQuery: String): List<String> {
        val cachedStrings = cache.get(key = cacheToRetrieve)
        return cachedStrings ?: getMapStrings(cacheToRetrieve, webUrl, className, cssQuery)
    }

    @Throws(IOException::class)
    private fun getMapStrings(
        cacheToRetrieve: String,
        webUrl: String,
        className: String,
        cssQuery: String
    ): List<String> {
        val mapElement = getElementFromWiki(webUrl, className)
        val listOfStrings = if (cacheToRetrieve == "dbdKillers") {
            getDbdKillerStrings(mapElement)
        } else {
            mapElement.select(cssQuery).eachText()
        }
        cache.put(cacheToRetrieve, listOfStrings)
        return listOfStrings
    }

    private fun getDbdKillerStrings(mapElement: Element): List<String> {
        return mapElement.select("div")[6]
            .allElements
            .eachText()
            .filter { name ->
                name.split("-").size == 2
            }
    }

    @Throws(IOException::class)
    private fun getElementFromWiki(webUrl: String, className: String): Element {
        val doc = Jsoup.connect(webUrl).get()
        return doc.getElementsByClass(className).first()
            ?: throw IOException("Element with class '$className' not found")
    }
}
