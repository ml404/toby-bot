package bot.toby.helpers

import java.net.URI
import java.net.URL

object URLHelper {
    fun isValidURL(url: String?): Boolean {
        /* Try creating a valid URL */
        try {
            URL(url).toURI()
            return true
        } // If there was an Exception
        // while creating URL object
        catch (e: Exception) {
            return false
        }
    }

    fun fromUrlString(url: String?): URI? {
        /* Try creating a valid URL */
        return try {
            URL(url).toURI()
        } // If there was an Exception
        // while creating URL object
        catch (e: Exception) {
            null
        }
    }
}
