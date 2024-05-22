package toby.dto.web;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RedditAPIDtoTest {

    @Test
    public void testTimePeriodEnumValues() {
        assertEquals("day", RedditAPIDto.TimePeriod.DAY.getTimePeriod());
        assertEquals("week", RedditAPIDto.TimePeriod.WEEK.getTimePeriod());
        assertEquals("month", RedditAPIDto.TimePeriod.MONTH.getTimePeriod());
        assertEquals("all", RedditAPIDto.TimePeriod.ALL.getTimePeriod());
    }

    @Test
    public void testDeserializeFromJson() {
        // Sample JSON representing a Reddit post
        String json = "{ " +
                "\"title\": \"Sample Reddit Post\", " +
                "\"author\": \"sample_author\", " +
                "\"permalink\": \"https://www.reddit.com/r/funny/sample-post\", " +
                "\"url_overridden_by_dest\": \"https://example.com/sample-image\", " +
                "\"over_18\": false, " +
                "\"is_video\": false " +
                "}";

        // Use Gson to deserialize the JSON into a RedditAPIDto object
        Gson gson = new Gson();
        RedditAPIDto redditAPIDto = gson.fromJson(json, RedditAPIDto.class);

        // Assert that the deserialized object has the expected values
        assertEquals("Sample Reddit Post", redditAPIDto.getTitle());
        assertEquals("sample_author", redditAPIDto.getAuthor());
        assertEquals("https://www.reddit.com/r/funny/sample-post", redditAPIDto.getUrl());
        assertFalse(redditAPIDto.isNsfw());
        assertFalse(redditAPIDto.getVideo());
    }

    @Test
    public void testTimePeriodValueOf() {
        assertEquals(RedditAPIDto.TimePeriod.DAY, RedditAPIDto.TimePeriod.parseTimePeriod("DAY"));
        assertEquals(RedditAPIDto.TimePeriod.WEEK, RedditAPIDto.TimePeriod.parseTimePeriod("WEEK"));
        assertEquals(RedditAPIDto.TimePeriod.MONTH, RedditAPIDto.TimePeriod.parseTimePeriod("MONTH"));
        assertEquals(RedditAPIDto.TimePeriod.ALL, RedditAPIDto.TimePeriod.parseTimePeriod("ALL"));
    }

    @Test
    public void testTimePeriodValueOfCaseInsensitive() {
        assertEquals(RedditAPIDto.TimePeriod.DAY, RedditAPIDto.TimePeriod.parseTimePeriod("day"));
        assertEquals(RedditAPIDto.TimePeriod.WEEK, RedditAPIDto.TimePeriod.parseTimePeriod("week"));
        assertEquals(RedditAPIDto.TimePeriod.MONTH, RedditAPIDto.TimePeriod.parseTimePeriod("month"));
        assertEquals(RedditAPIDto.TimePeriod.ALL, RedditAPIDto.TimePeriod.parseTimePeriod("all"));
    }

    @Test
    public void testTimePeriodValueOfInvalid() {
        // This test should throw an IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> RedditAPIDto.TimePeriod.parseTimePeriod("INVALID_VALUE"));
    }
}