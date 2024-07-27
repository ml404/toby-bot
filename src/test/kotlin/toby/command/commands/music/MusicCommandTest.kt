package toby.command.commands.music

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import io.mockk.*
import net.dv8tion.jda.api.entities.GuildVoiceState
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion
import net.dv8tion.jda.api.interactions.Interaction
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.managers.AudioManager
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction
import net.dv8tion.jda.api.requests.restaction.MessageEditAction
import toby.command.CommandTest
import toby.command.CommandTest.Companion.guild
import toby.command.CommandTest.Companion.interactionHook
import toby.command.CommandTest.Companion.message
import toby.command.CommandTest.Companion.webhookMessageCreateAction
import toby.helpers.MusicPlayerHelper.resetMessages
import toby.lavaplayer.AudioPlayerSendHandler
import toby.lavaplayer.GuildMusicManager
import toby.lavaplayer.PlayerManager
import toby.lavaplayer.TrackScheduler
import java.util.concurrent.ArrayBlockingQueue

interface MusicCommandTest : CommandTest {
    fun setupCommonMusicMocks() {
        setUpCommonMocks()
        every { interactionHook.interaction } returns interaction
        every { interaction.guild } returns guild
        every { playerManager.getMusicManager(guild) } returns musicManager
        every { musicManager.audioPlayer } returns mockAudioPlayer
        every { musicManager.sendHandler } returns audioPlayerSendHandler
        every { musicManager.scheduler } returns trackScheduler
        every { guild.audioManager } returns audioManager
        every { mockAudioPlayer.playingTrack } returns track
        every { trackScheduler.queue } returns ArrayBlockingQueue(1)
        every { track.info } returns AudioTrackInfo("Title", "Author", 20L, "Identifier", true, "uri")
        every { track.duration } returns 1000L

        val pausePlay = Button.primary("pause/play", "⏯")
        val stop = Button.primary("stop", "⏹")

        every { webhookMessageCreateAction.setActionRow(listOf(pausePlay, stop)) } returns webhookMessageCreateAction
        every { webhookMessageCreateAction.complete() } returns message
        every { webhookMessageCreateAction.setActionRow(any(), any()) } returns webhookMessageCreateAction
        every { message.delete() } returns auditableRestAction as AuditableRestAction<Void>
        every { auditableRestAction.queue() } just Runs
        every { message.editMessage(any<String>()) } returns messageEditAction
        every { messageEditAction.setActionRow(any(), any()) } returns messageEditAction

        resetMessages(guild.idLong)
    }

    fun tearDownCommonMusicMocks() {
        tearDownCommonMocks()
        unmockkAll()
    }

    fun setUpAudioChannelsWithBotAndMemberInSameChannel() {
        every { guild.selfMember } returns botMember
        every { CommandTest.member.voiceState } returns memberVoiceState
        every { memberVoiceState.channel } returns audioChannelUnion
        every { botMember.voiceState } returns botVoiceState
        every { botVoiceState.channel } returns audioChannelUnion
        every { guild.audioManager } returns audioManager
        every { memberVoiceState.inAudioChannel() } returns true
        every { botVoiceState.inAudioChannel() } returns true
    }

    fun setUpAudioChannelsWithBotNotInChannel() {
        every { guild.selfMember } returns botMember
        every { CommandTest.member.voiceState } returns memberVoiceState
        every { botMember.voiceState } returns botVoiceState
        every { memberVoiceState.inAudioChannel() } returns true
        every { memberVoiceState.channel } returns audioChannelUnion
        every { botVoiceState.inAudioChannel() } returns false
        every { botVoiceState.channel } returns null
        every { guild.audioManager } returns audioManager
    }

    fun setUpAudioChannelsWithUserNotInChannel() {
        val audioManager = mockk<AudioManager>()
        every { guild.selfMember } returns botMember
        every { CommandTest.member.voiceState } returns memberVoiceState
        every { botMember.voiceState } returns botVoiceState
        every { memberVoiceState.inAudioChannel() } returns false
        every { memberVoiceState.channel } returns null
        every { botVoiceState.inAudioChannel() } returns true
        every { botVoiceState.channel } returns audioChannelUnion
        every { guild.audioManager } returns audioManager
    }

    fun setUpAudioChannelsWithUserAndBotInDifferentChannels() {
        every { guild.selfMember } returns botMember
        every { CommandTest.member.voiceState } returns memberVoiceState
        every { botMember.voiceState } returns botVoiceState
        every { memberVoiceState.inAudioChannel() } returns true
        every { memberVoiceState.channel } returns mockk()
        every { botVoiceState.inAudioChannel() } returns true
        every { botVoiceState.channel } returns audioChannelUnion
        every { guild.audioManager } returns audioManager
    }

    companion object {
        val memberVoiceState: GuildVoiceState = mockk(relaxed = true)
        val botVoiceState: GuildVoiceState = mockk(relaxed = true)
        val botMember: Member = mockk(relaxed = true)
        var playerManager: PlayerManager = mockk(relaxed = true)
        val musicManager: GuildMusicManager = mockk(relaxed = true)
        val mockAudioPlayer: AudioPlayer = mockk(relaxed = true)
        val audioManager: AudioManager = mockk(relaxed = true)
        val audioPlayerSendHandler: AudioPlayerSendHandler = mockk(relaxed = true)
        val track: AudioTrack = mockk(relaxed = true)
        val trackScheduler: TrackScheduler = mockk(relaxed = true)
        val audioChannelUnion: AudioChannelUnion = mockk(relaxed = true)
        val interaction: Interaction = mockk(relaxed = true)
        val auditableRestAction: AuditableRestAction<*> = mockk(relaxed = true)
        val messageEditAction: MessageEditAction = mockk(relaxed = true)
    }
}
