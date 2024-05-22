package toby.command.commands.moderation

import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.GuildVoiceState
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import toby.command.CommandContext
import toby.command.CommandTest

internal class TalkCommandTest : CommandTest {
    var talkCommand: TalkCommand? = null

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        talkCommand = TalkCommand()
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
    }

    @Test
    fun test_talkWithValidPermissions_unmutesEveryoneInChannel() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        talkSetup(true, true, listOf(CommandTest.targetMember))

        //Act
        talkCommand!!.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.event, Mockito.times(1)).guild
        Mockito.verify(CommandTest.guild, Mockito.times(1))
            .mute(CommandTest.targetMember, false)
    }

    @Test
    fun test_talkWithValidPermissionsAndMultipleMembers_unmutesEveryoneInChannel() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        talkSetup(true, true, listOf(CommandTest.member, CommandTest.targetMember))

        //Act
        talkCommand!!.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.event, Mockito.times(1)).guild
        Mockito.verify(CommandTest.guild, Mockito.times(1)).mute(CommandTest.member, false)
        Mockito.verify(CommandTest.guild, Mockito.times(1))
            .mute(CommandTest.targetMember, false)
    }


    @Test
    fun test_talkWithInvalidBotPermissions_throwsError() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        talkSetup(false, true, listOf(CommandTest.targetMember))

        //Act
        talkCommand!!.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.event, Mockito.times(1)).guild
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("I'm not allowed to unmute %s"),
            ArgumentMatchers.eq(CommandTest.targetMember)
        )
    }

    @Test
    fun test_talkWithInvalidUserPermissions_throwsError() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        talkSetup(true, false, listOf(CommandTest.targetMember))

        //Act
        talkCommand!!.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.event, Mockito.times(1)).guild
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("You aren't allowed to unmute %s"),
            ArgumentMatchers.eq(CommandTest.targetMember)
        )
    }

    companion object {
        private fun talkSetup(voiceMuteOtherBot: Boolean, voiceMuteOtherMember: Boolean, targetMember: List<Member>) {
            val audioChannelUnion = Mockito.mock(AudioChannelUnion::class.java)
            val guildVoiceState = Mockito.mock(GuildVoiceState::class.java)
            val auditableRestAction = Mockito.mock(AuditableRestAction::class.java)
            Mockito.`when`(
                CommandTest.member.canInteract(
                    ArgumentMatchers.any(
                        Member::class.java
                    )
                )
            ).thenReturn(true)
            Mockito.`when`(CommandTest.botMember.hasPermission(Permission.VOICE_MUTE_OTHERS))
                .thenReturn(voiceMuteOtherBot)
            Mockito.`when`<GuildVoiceState>(CommandTest.member.voiceState).thenReturn(guildVoiceState)
            Mockito.`when`(CommandTest.member.hasPermission(Permission.VOICE_MUTE_OTHERS))
                .thenReturn(voiceMuteOtherMember)
            Mockito.`when`(guildVoiceState.channel).thenReturn(audioChannelUnion)
            Mockito.`when`(audioChannelUnion.members).thenReturn(targetMember)
            Mockito.`when`<AuditableRestAction<Void>>(
                CommandTest.guild.mute(
                    ArgumentMatchers.any(),
                    ArgumentMatchers.eq(false)
                )
            ).thenReturn(auditableRestAction as AuditableRestAction<Void>)
            Mockito.`when`(auditableRestAction.reason("Unmuted")).thenReturn(auditableRestAction)
        }
    }
}