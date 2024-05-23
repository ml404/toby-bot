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

internal class ShhCommandTest : CommandTest {
    private var shhCommand: ShhCommand? = null

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        shhCommand = ShhCommand()
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
    }

    @Test
    fun test_shhWithValidPermissions_mutesEveryoneInChannel() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        shhSetup(voiceMuteOtherBot = true, voiceMuteOtherMember = true, targetMember = listOf(CommandTest.targetMember))

        //Act
        shhCommand!!.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.event, Mockito.times(1)).guild
        Mockito.verify(CommandTest.guild, Mockito.times(1))
            .mute(CommandTest.targetMember, true)
    }

    @Test
    fun test_shhWithValidPermissionsAndMultipleMembers_mutesEveryoneInChannel() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        shhSetup(
            voiceMuteOtherBot = true,
            voiceMuteOtherMember = true,
            targetMember = listOf(CommandTest.member, CommandTest.targetMember)
        )

        //Act
        shhCommand!!.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.event, Mockito.times(1)).guild
        Mockito.verify(CommandTest.guild, Mockito.times(1)).mute(CommandTest.member, true)
        Mockito.verify(CommandTest.guild, Mockito.times(1))
            .mute(CommandTest.targetMember, true)
    }


    @Test
    fun test_shhWithInvalidBotPermissions_throwsError() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        shhSetup(false, true, listOf(CommandTest.targetMember))

        //Act
        shhCommand!!.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.event, Mockito.times(1)).guild
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("I'm not allowed to mute %s"),
            ArgumentMatchers.eq(CommandTest.targetMember)
        )
    }

    @Test
    fun test_shhWithInvalidUserPermissions_throwsError() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        shhSetup(true, false, listOf(CommandTest.targetMember))

        //Act
        shhCommand!!.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.event, Mockito.times(1)).guild
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("You aren't allowed to mute %s"),
            ArgumentMatchers.eq(CommandTest.targetMember)
        )
    }

    companion object {
        private fun shhSetup(voiceMuteOtherBot: Boolean, voiceMuteOtherMember: Boolean, targetMember: List<Member>) {
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
                    ArgumentMatchers.eq(true)
                )
            ).thenReturn(auditableRestAction as AuditableRestAction<Void>)
            Mockito.`when`(auditableRestAction.reason("Muted")).thenReturn(auditableRestAction)
        }
    }
}