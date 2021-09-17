package toby.helpers;

import java.net.URI;
import java.net.URL;

public class URLHelper {

    public static URI isValidURL(String url) {
        /* Try creating a valid URL */
        try {
          return new URL(url).toURI();
        }
        // If there was an Exception
        // while creating URL object
        catch (Exception e) {
            return null;
        }
    }
}
