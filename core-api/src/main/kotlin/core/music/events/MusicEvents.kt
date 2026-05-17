package core.music.events

import core.music.MusicControlGateway.TrackInfo

sealed class MusicEvent {
    abstract val guildId: Long
}

data class TrackStartedEvent(
    override val guildId: Long,
    val track: TrackInfo,
) : MusicEvent()

data class TrackEndedEvent(
    override val guildId: Long,
    val endReason: String,
) : MusicEvent()

data class QueueChangedEvent(
    override val guildId: Long,
    val queue: List<TrackInfo>,
) : MusicEvent()

data class PauseStateChangedEvent(
    override val guildId: Long,
    val paused: Boolean,
) : MusicEvent()

data class VolumeChangedEvent(
    override val guildId: Long,
    val volume: Int,
) : MusicEvent()

data class LoopStateChangedEvent(
    override val guildId: Long,
    val looping: Boolean,
) : MusicEvent()
