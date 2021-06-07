package toby.helpers;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

public class WikiFetcher {

    private final Cache cache;

    public WikiFetcher(Cache cache) {
        this.cache = cache;
    }

    @SuppressWarnings("unchecked")
    public List<String> fetchFromWiki(String cacheToRetrieve, String webUrl, String className, String cssQuery) throws IOException {
        List<String> listOfStrings = (List<String>) cache.get(cacheToRetrieve);
        return listOfStrings != null ? listOfStrings : getMapStrings(cacheToRetrieve, webUrl, className,cssQuery);
    }

    @SuppressWarnings("unchecked")
    private List<String> getMapStrings(String cacheToRetrieve, String webUrl, String className, String cssQuery) throws IOException {
        Element mapElement = getElementFromWiki(webUrl, className);
        List<String> listOfStrings = cacheToRetrieve.equals("dbdKillers") ? getDbdKillerStrings(mapElement) : mapElement.select(cssQuery).eachText();
        cache.put(cacheToRetrieve, listOfStrings);
        return listOfStrings;
    }

    public List<String> getDbdKillerStrings(Element mapElement){
        return mapElement.select("div").get(6).getAllElements().eachText().stream().filter(name -> name.split("-").length == 2).collect(Collectors.toList());
    }

    private Element getElementFromWiki(String webUrl, String className) throws IOException {
        Document doc = Jsoup.connect(webUrl).get();
        Elements mapElements = doc.getElementsByClass(className);
        return mapElements.get(0);
    }
}

