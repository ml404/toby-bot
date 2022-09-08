package toby.helpers;

import java.net.URI;
import java.net.URL;

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

    public static URI fromUrlString(String url){
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
