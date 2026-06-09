package bot.toby.dto.web

import com.google.gson.annotations.SerializedName

class RedditAPIDto {
    @JvmField
    @SerializedName("title")
    var title: String? = null

    @JvmField
    @SerializedName("author")
    var author: String? = null

    @JvmField
    @SerializedName("permalink")
    var url: String? = null

    @SerializedName("over_18")
    var isNsfw: Boolean? = null

    @JvmField
    @SerializedName("is_video")
    var video: Boolean? = null

    enum class TimePeriod(@JvmField val timePeriod: String) {
        DAY("day"),
        WEEK("week"),
        MONTH("month"),
        ALL("all");

        companion object {
            @JvmStatic
            fun parseTimePeriod(value: String): TimePeriod {
                for (timePeriod in entries) {
                    if (timePeriod.name.equals(value, ignoreCase = true)) {
                        return timePeriod
                    }
                }
                // Handle non-enum constants here
                throw IllegalArgumentException("Invalid time period: $value")
            }
        }
    }

    companion object {
        /** Legacy unauthenticated endpoint — Reddit now rate-limits/blocks this; kept as a fallback when no creds are set. */
        const val REDDIT_PREFIX: String = "https://old.reddit.com/r/%s/top/.json?limit=%d&t=%s"

        /** Authenticated endpoint used with an app-only bearer token (issue #107). */
        const val REDDIT_OAUTH_PREFIX: String = "https://oauth.reddit.com/r/%s/top.json?limit=%d&t=%s&raw_json=1"
    }
}
