package bot.toby.util

import java.awt.Color

data class SourceBadge(
    val displayName: String,
    val color: Color,
    val iconUrl: String?,
    val homeUrl: String?,
)

object SourceBadges {
    private val DEFAULT = SourceBadge("Music", Color(0x57F287), null, null)

    private val BADGES: Map<String, SourceBadge> = mapOf(
        "youtube" to SourceBadge(
            displayName = "YouTube",
            color = Color(0xFF0000),
            iconUrl = "https://www.youtube.com/s/desktop/14a6f4f0/img/favicon_144x144.png",
            homeUrl = "https://www.youtube.com",
        ),
        "spotify" to SourceBadge(
            displayName = "Spotify",
            color = Color(0x1DB954),
            iconUrl = "https://open.scdn.co/cdn/images/favicon32.b64ecc03.png",
            homeUrl = "https://open.spotify.com",
        ),
        "soundcloud" to SourceBadge(
            displayName = "SoundCloud",
            color = Color(0xFF5500),
            iconUrl = "https://a-v2.sndcdn.com/assets/images/sc-icons/favicon-2cadd14bdb.ico",
            homeUrl = "https://soundcloud.com",
        ),
        "applemusic" to SourceBadge(
            displayName = "Apple Music",
            color = Color(0xFA243C),
            iconUrl = "https://music.apple.com/assets/favicon/favicon-180-67f9ec40c4f9c8c80de2c8d11d22fab8.png",
            homeUrl = "https://music.apple.com",
        ),
        "bandcamp" to SourceBadge(
            displayName = "Bandcamp",
            color = Color(0x1DA0C3),
            iconUrl = "https://s4.bcbits.com/img/favicon/favicon-32x32.png",
            homeUrl = "https://bandcamp.com",
        ),
        "vimeo" to SourceBadge(
            displayName = "Vimeo",
            color = Color(0x1AB7EA),
            iconUrl = "https://i.vimeocdn.com/favicon/main-touch_180",
            homeUrl = "https://vimeo.com",
        ),
        "deezer" to SourceBadge(
            displayName = "Deezer",
            color = Color(0xA238FF),
            iconUrl = "https://e-cdns-files.dzcdn.net/cache/images/common/favicon/favicon-32x32.61f8aa8e.png",
            homeUrl = "https://www.deezer.com",
        ),
        "yandexmusic" to SourceBadge(
            displayName = "Yandex Music",
            color = Color(0xFFCC00),
            iconUrl = "https://music.yandex.ru/blocks/common/favicon.ico",
            homeUrl = "https://music.yandex.ru",
        ),
        "twitch" to SourceBadge(
            displayName = "Twitch",
            color = Color(0x9146FF),
            iconUrl = "https://static.twitchcdn.net/assets/favicon-32-d6025c14e900565d6177.png",
            homeUrl = "https://www.twitch.tv",
        ),
        "nico" to SourceBadge(
            displayName = "Niconico",
            color = Color(0x252525),
            iconUrl = null,
            homeUrl = "https://www.nicovideo.jp",
        ),
        "http" to SourceBadge("Direct Stream", Color(0x5865F2), null, null),
        "local" to SourceBadge("Local File", Color(0x99AAB5), null, null),
    )

    fun forSource(sourceName: String?): SourceBadge {
        if (sourceName == null) return DEFAULT
        return BADGES[sourceName.lowercase()] ?: DEFAULT
    }
}
