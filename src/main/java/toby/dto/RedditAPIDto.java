package toby.dto;

import com.google.gson.annotations.SerializedName;

public class RedditAPIDto {

    @SerializedName("title")
    private String title;

    @SerializedName("author")
    private String author;

    @SerializedName("url")
    private String url;

    @SerializedName("url_overridden_by_dest")
    private String image;

    @SerializedName("over_18")
    private Boolean nsfw;

    @SerializedName("is_video")
    private Boolean video;


    public static String redditPrefix ="https://old.reddit.com/r/%s/top/.json";

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
