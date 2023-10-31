package toby.dto.web;

import com.google.gson.annotations.SerializedName;

public class RedditAPIDto {

    @SerializedName("title")
    private String title;

    @SerializedName("author")
    private String author;

    @SerializedName("permalink")
    private String url;

    @SerializedName("url_overridden_by_dest")
    private String image;

    @SerializedName("over_18")
    private Boolean nsfw;

    @SerializedName("is_video")
    private Boolean video;

    public enum TimePeriod {
        DAY("day"),
        WEEK("week"),
        MONTH("month"),
        ALL("all");

        private final String timePeriod;

        TimePeriod(String timePeriod){
            this.timePeriod=timePeriod;
        }

        public String getTimePeriod(){
            return this.timePeriod;
        }

        public static RedditAPIDto.TimePeriod parseTimePeriod(String value) {
            for (RedditAPIDto.TimePeriod timePeriod : RedditAPIDto.TimePeriod.values()) {
                if (timePeriod.name().equalsIgnoreCase(value)) {
                    return timePeriod;
                }
            }
            // Handle non-enum constants here
            throw new IllegalArgumentException("Invalid time period: " + value);
        }

    }

    public static String redditPrefix = "https://old.reddit.com/r/%s/top/.json?limit=%d&t=%s";

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Boolean isNsfw() {
        return nsfw;
    }

    public void setNsfw(Boolean nsfw) {
        this.nsfw = nsfw;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public Boolean getVideo() {
        return video;
    }

    public void setVideo(Boolean video) {
        this.video = video;
    }

}
