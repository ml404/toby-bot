package bot.toby.util

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import database.dto.music.MusicDto
import java.util.concurrent.TimeUnit

private const val SECOND_MULTIPLIER = 1000

private val URL_REGEX = Regex(
    """\b(https?://[^\s/$.?#].\S*)\b""",
    RegexOption.IGNORE_CASE,
)

fun formatTime(timeInMillis: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(timeInMillis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(timeInMillis) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(timeInMillis) % 60
    return String.format("%02d:%02d:%02d", hours, minutes, seconds)
}

fun isUrl(content: String): String = URL_REGEX.find(content)?.value ?: ""

fun adjustTrackPlayingTimes(startTime: Long): Long {
    val adjustmentMap = mutableMapOf<String, Long>()
    if (startTime > 0L) adjustmentMap[MusicDto.Adjustment.START.name] = startTime
    return adjustmentMap[MusicDto.Adjustment.START.name]?.times(SECOND_MULTIPLIER) ?: 0L
}

fun deriveDeleteDelayFromTrack(track: AudioTrack): Int =
    (track.duration / SECOND_MULTIPLIER).toInt()
