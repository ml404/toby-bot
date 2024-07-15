package toby.helpers

import java.net.URI
import java.net.URL
import java.util.*

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

    fun fromUrlString(url: String?): Optional<URI> {
        /* Try creating a valid URL */
        return try {
            Optional.of(URL(url).toURI())
        } // If there was an Exception
        // while creating URL object
        catch (e: Exception) {
            Optional.empty()
        }
    }
}
