package toby.dto.web

import com.google.gson.Gson
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import toby.dto.web.RedditAPIDto.TimePeriod.Companion.parseTimePeriod

internal class RedditAPIDtoTest {
    @Test
    fun testTimePeriodEnumValues() {
        Assertions.assertEquals("day", RedditAPIDto.TimePeriod.DAY.timePeriod)
        Assertions.assertEquals("week", RedditAPIDto.TimePeriod.WEEK.timePeriod)
        Assertions.assertEquals("month", RedditAPIDto.TimePeriod.MONTH.timePeriod)
        Assertions.assertEquals("all", RedditAPIDto.TimePeriod.ALL.timePeriod)
    }

    @Test
    fun testDeserializeFromJson() {
        // Sample JSON representing a Reddit post
        val json = "{ " +
                "\"title\": \"Sample Reddit Post\", " +
                "\"author\": \"sample_author\", " +
                "\"permalink\": \"https://www.reddit.com/r/funny/sample-post\", " +
                "\"url_overridden_by_dest\": \"https://example.com/sample-image\", " +
                "\"over_18\": false, " +
                "\"is_video\": false " +
                "}"

        // Use Gson to deserialize the JSON into a RedditAPIDto object
        val gson = Gson()
        val redditAPIDto = gson.fromJson(json, RedditAPIDto::class.java)

        // Assert that the deserialized object has the expected values
        Assertions.assertEquals("Sample Reddit Post", redditAPIDto.title)
        Assertions.assertEquals("sample_author", redditAPIDto.author)
        Assertions.assertEquals("https://www.reddit.com/r/funny/sample-post", redditAPIDto.url)
        Assertions.assertFalse(redditAPIDto.isNsfw!!)
        Assertions.assertFalse(redditAPIDto.video!!)
    }

    @Test
    fun testTimePeriodValueOf() {
        Assertions.assertEquals(RedditAPIDto.TimePeriod.DAY, parseTimePeriod("DAY"))
        Assertions.assertEquals(RedditAPIDto.TimePeriod.WEEK, parseTimePeriod("WEEK"))
        Assertions.assertEquals(RedditAPIDto.TimePeriod.MONTH, parseTimePeriod("MONTH"))
        Assertions.assertEquals(RedditAPIDto.TimePeriod.ALL, parseTimePeriod("ALL"))
    }

    @Test
    fun testTimePeriodValueOfCaseInsensitive() {
        Assertions.assertEquals(RedditAPIDto.TimePeriod.DAY, parseTimePeriod("day"))
        Assertions.assertEquals(RedditAPIDto.TimePeriod.WEEK, parseTimePeriod("week"))
        Assertions.assertEquals(RedditAPIDto.TimePeriod.MONTH, parseTimePeriod("month"))
        Assertions.assertEquals(RedditAPIDto.TimePeriod.ALL, parseTimePeriod("all"))
    }

    @Test
    fun testTimePeriodValueOfInvalid() {
        // This test should throw an IllegalArgumentException
        Assertions.assertThrows(IllegalArgumentException::class.java) { parseTimePeriod("INVALID_VALUE") }
    }
}