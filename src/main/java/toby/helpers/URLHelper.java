package toby.helpers;

import java.net.URI;
import java.net.URL;
import java.util.Optional;

public class URLHelper {

    public static boolean isValidURL(String url) {
        /* Try creating a valid URL */
        try {
            new URL(url).toURI();
            return true;
        }
        // If there was an Exception
        // while creating URL object
        catch (Exception e) {
            return false;
        }
    }

    public static Optional<URI> fromUrlString(String url){
        /* Try creating a valid URL */
        try {
            return Optional.of(new URL(url).toURI());
        }
        // If there was an Exception
        // while creating URL object
        catch (Exception e) {
            return Optional.empty();
        }
    }
}
