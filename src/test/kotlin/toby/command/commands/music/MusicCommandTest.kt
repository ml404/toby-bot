package toby.command.commands.music

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.GuildVoiceState
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion
import net.dv8tion.jda.api.interactions.Interaction
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.managers.AudioManager
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction
import net.dv8tion.jda.api.requests.restaction.MessageEditAction
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito
import toby.command.CommandTest
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
        Mockito.`when`(CommandTest.interactionHook.interaction).thenReturn(interaction)
        Mockito.`when`<Guild?>(interaction.guild).thenReturn(CommandTest.guild)
        Mockito.`when`(playerManager.getMusicManager(CommandTest.guild)).thenReturn(musicManager)
        Mockito.`when`(musicManager.getAudioPlayer()).thenReturn(audioPlayer)
        Mockito.`when`(musicManager.getSendHandler()).thenReturn(audioPlayerSendHandler)
        Mockito.`when`(musicManager.getScheduler()).thenReturn(trackScheduler)
        Mockito.`when`(CommandTest.guild.audioManager).thenReturn(audioManager)
        Mockito.`when`(audioPlayer.playingTrack).thenReturn(track)
        Mockito.`when`(trackScheduler.getQueue()).thenReturn(ArrayBlockingQueue(1))
        Mockito.`when`(track.info).thenReturn(AudioTrackInfo("Title", "Author", 20L, "Identifier", true, "uri"))
        Mockito.`when`(track.duration).thenReturn(1000L)
        val pausePlay = Button.primary("pause/play", "⏯")
        val stop = Button.primary("stop", "⏹")
        Mockito.`when`<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction.addActionRow(
                pausePlay,
                stop
            ) as WebhookMessageCreateAction<Message>?
        ).thenReturn(webhookMessageCreateAction as WebhookMessageCreateAction<Message>?)
        Mockito.`when`(webhookMessageCreateAction.complete())
            .thenReturn(CommandTest.message)
        Mockito.`when`<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction.setActionRow(
                ArgumentMatchers.any(),
                ArgumentMatchers.any()
            )
        ).thenReturn(webhookMessageCreateAction)
        Mockito.`when`<AuditableRestAction<Void>>(CommandTest.message.delete())
            .thenReturn(auditableRestAction as AuditableRestAction<Void>)
        Mockito.`when`(CommandTest.message.editMessage(ArgumentMatchers.anyString()))
            .thenReturn(
                messageEditAction
            )
        Mockito.`when`(messageEditAction.setActionRow(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(
            messageEditAction
        )
        resetMessages(CommandTest.guild.idLong)
    }

    fun tearDownCommonMusicMocks() {
        tearDownCommonMocks()
        Mockito.reset(playerManager)
        Mockito.reset(musicManager)
        Mockito.reset(CommandTest.guild)
        Mockito.reset(trackScheduler)
        Mockito.reset(track)
        Mockito.reset(audioPlayer)
        Mockito.reset(audioChannelUnion)
        Mockito.reset(memberVoiceState)
        Mockito.reset(botVoiceState)
        Mockito.reset(interaction)
        Mockito.reset(CommandTest.message)
        Mockito.reset(messageEditAction)
    }

    fun setUpAudioChannelsWithBotAndMemberInSameChannel() {
        Mockito.`when`(CommandTest.guild.selfMember).thenReturn(botMember)
        Mockito.`when`<GuildVoiceState>(CommandTest.member.voiceState).thenReturn(memberVoiceState)
        Mockito.`when`(memberVoiceState.channel).thenReturn(audioChannelUnion)
        Mockito.`when`(botMember.voiceState).thenReturn(botVoiceState)
        Mockito.`when`(botVoiceState.channel).thenReturn(audioChannelUnion)
        Mockito.`when`(CommandTest.guild.audioManager).thenReturn(audioManager)
        Mockito.`when`(memberVoiceState.inAudioChannel()).thenReturn(true)
        Mockito.`when`(botVoiceState.inAudioChannel()).thenReturn(true)
    }

    fun setUpAudioChannelsWithBotNotInChannel() {
        Mockito.`when`(CommandTest.guild.selfMember).thenReturn(botMember)
        Mockito.`when`<GuildVoiceState>(CommandTest.member.voiceState).thenReturn(memberVoiceState)
        Mockito.`when`(botMember.voiceState).thenReturn(botVoiceState)
        Mockito.`when`(memberVoiceState.inAudioChannel()).thenReturn(true)
        Mockito.`when`(memberVoiceState.channel).thenReturn(audioChannelUnion)
        Mockito.`when`(botVoiceState.inAudioChannel()).thenReturn(false)
        Mockito.`when`(botVoiceState.channel).thenReturn(null)
        Mockito.`when`(CommandTest.guild.audioManager).thenReturn(audioManager)
    }

    fun setUpAudioChannelsWithUserNotInChannel() {
        val audioManager = Mockito.mock(AudioManager::class.java)
        Mockito.`when`(CommandTest.guild.selfMember).thenReturn(botMember)
        Mockito.`when`<GuildVoiceState>(CommandTest.member.voiceState).thenReturn(memberVoiceState)
        Mockito.`when`(botMember.voiceState).thenReturn(botVoiceState)
        Mockito.`when`(memberVoiceState.inAudioChannel()).thenReturn(false)
        Mockito.`when`(memberVoiceState.channel).thenReturn(null)
        Mockito.`when`(botVoiceState.inAudioChannel()).thenReturn(true)
        Mockito.`when`(botVoiceState.channel).thenReturn(audioChannelUnion)
        Mockito.`when`(CommandTest.guild.audioManager).thenReturn(audioManager)
    }

    fun setUpAudioChannelsWithUserAndBotInDifferentChannels() {
        Mockito.`when`(CommandTest.guild.selfMember).thenReturn(botMember)
        Mockito.`when`<GuildVoiceState>(CommandTest.member.voiceState).thenReturn(memberVoiceState)
        Mockito.`when`(botMember.voiceState).thenReturn(botVoiceState)
        Mockito.`when`(memberVoiceState.inAudioChannel()).thenReturn(true)
        Mockito.`when`(memberVoiceState.channel).thenReturn(
            Mockito.mock(
                AudioChannelUnion::class.java
            )
        )
        Mockito.`when`(botVoiceState.inAudioChannel()).thenReturn(true)
        Mockito.`when`(botVoiceState.channel).thenReturn(audioChannelUnion)
        Mockito.`when`(CommandTest.guild.audioManager).thenReturn(audioManager)
    }

    companion object {
        @Mock
        val memberVoiceState: GuildVoiceState = Mockito.mock(GuildVoiceState::class.java)

        @Mock
        val botVoiceState: GuildVoiceState = Mockito.mock(GuildVoiceState::class.java)

        @Mock
        val botMember: Member = Mockito.mock(Member::class.java)

        @Mock
        val playerManager: PlayerManager = Mockito.mock(PlayerManager::class.java)

        @Mock
        val musicManager: GuildMusicManager = Mockito.mock(GuildMusicManager::class.java)

        @Mock
        val audioPlayer: AudioPlayer = Mockito.mock(AudioPlayer::class.java)

        @Mock
        val audioManager: AudioManager = Mockito.mock(AudioManager::class.java)

        @Mock
        val audioPlayerSendHandler: AudioPlayerSendHandler = Mockito.mock(AudioPlayerSendHandler::class.java)

        @Mock
        val track: AudioTrack = Mockito.mock(
            AudioTrack::class.java
        )

        @Mock
        val trackScheduler: TrackScheduler = Mockito.mock(TrackScheduler::class.java)

        @Mock
        val audioChannelUnion: AudioChannelUnion = Mockito.mock(AudioChannelUnion::class.java)

        @Mock
        val interaction: Interaction = Mockito.mock(
            Interaction::class.java
        )

        @Mock
        val auditableRestAction: AuditableRestAction<*> = Mockito.mock(AuditableRestAction::class.java)

        @Mock
        val messageEditAction: MessageEditAction = Mockito.mock(MessageEditAction::class.java)
    }
}
