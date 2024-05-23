package toby.lavaplayer

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import kotlin.math.max
import kotlin.math.min

object TrackBoxBuilder {
    private const val TOP_LEFT_CORNER = "\u2554"
    private const val TOP_RIGHT_CORNER = "\u2557"
    private const val BOTTOM_LEFT_CORNER = "\u255a"
    private const val BOTTOM_RIGHT_CORNER = "\u255d"
    private const val BORDER_HORIZONTAL = "\u2550"
    private const val BORDER_VERTICAL = "\u2551"
    private const val PROGRESS_FILL = "\u25a0"
    private const val PROGRESS_EMPTY = "\u2015"

    fun buildTrackBox(width: Int, track: AudioTrack, isPaused: Boolean, volume: Int): String {
        return boxify(width, buildFirstLine(width - 4, track), buildSecondLine(width - 4, track, isPaused, volume))
    }

    private fun buildFirstLine(width: Int, track: AudioTrack): String {
        val builder = StringBuilder()
        val title = track.info.title
        val titleWidth = width - 7

        if (title.length > titleWidth) {
            builder.append(title.substring(0, titleWidth - 3))
            builder.append("...")
        } else {
            builder.append(title)
        }

        return builder.toString()
    }

    private fun buildSecondLine(width: Int, track: AudioTrack, isPaused: Boolean, volume: Int): String {
        val cornerText = if (isPaused) "PAUSED" else "$volume%"

        val duration = formatTiming(track.duration, track.duration)
        val position = formatTiming(track.position, track.duration)
        val spacing = duration.length - position.length
        val barLength = width - duration.length - position.length - spacing - 14

        val progress =
            min(track.position.toDouble(), track.duration.toDouble()).toFloat() / max(track.duration.toDouble(), 1.0)
                .toFloat()
        val progressBlocks = Math.round(progress * barLength)

        val builder = StringBuilder()

        for (i in 0 until 6 - cornerText.length) {
            builder.append(" ")
        }

        builder.append(cornerText)

        builder.append(" [")
        for (i in 0 until barLength) {
            builder.append(if (i < progressBlocks) PROGRESS_FILL else PROGRESS_EMPTY)
        }
        builder.append("]")

        for (i in 0 until spacing + 1) {
            builder.append(" ")
        }

        builder.append(position)
        builder.append(" of ")
        builder.append(duration)

        builder.append(" ")
        builder.append(TOP_RIGHT_CORNER)

        return builder.toString()
    }

    private fun formatTiming(timing: Long, maximum: Long): String {
        var timing = timing
        timing = (min(timing.toDouble(), maximum.toDouble()) / 1000).toLong()

        val seconds = timing % 60
        timing /= 60
        val minutes = timing % 60
        timing /= 60
        val hours = timing

        return if (maximum >= 3600000L) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    private fun boxifyLine(builder: StringBuilder, line: String) {
        builder.append(BORDER_VERTICAL)
        builder.append(" ")
        builder.append(line)
        builder.append("\n")
    }

    private fun boxify(width: Int, firstLine: String, secondLine: String): String {
        val builder = StringBuilder()

        builder.append("```")
        builder.append(TOP_LEFT_CORNER)
        for (i in 0 until width - 1) {
            builder.append(BORDER_HORIZONTAL)
        }
        builder.append("\n")

        boxifyLine(builder, firstLine)
        boxifyLine(builder, secondLine)

        builder.append(BOTTOM_LEFT_CORNER)
        for (i in 0 until width - 2) {
            builder.append(BORDER_HORIZONTAL)
        }
        builder.append(BOTTOM_RIGHT_CORNER)
        builder.append("```")

        return builder.toString()
    }
}



