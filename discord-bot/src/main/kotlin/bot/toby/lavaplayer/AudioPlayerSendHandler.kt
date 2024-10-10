package bot.toby.lavaplayer

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import net.dv8tion.jda.api.audio.AudioSendHandler
import java.nio.Buffer
import java.nio.ByteBuffer

class AudioPlayerSendHandler(private val audioPlayer: AudioPlayer) : AudioSendHandler {
    private val buffer: ByteBuffer = ByteBuffer.allocate(1024)
    private val frame = MutableAudioFrame()

    init {
        frame.setBuffer(buffer)
    }

    override fun canProvide(): Boolean {
        return audioPlayer.provide(this.frame)
    }

    override fun provide20MsAudio(): ByteBuffer {
        val buffer = (buffer as Buffer).flip()
        return buffer as ByteBuffer
    }

    override fun isOpus(): Boolean {
        return true
    }
}

