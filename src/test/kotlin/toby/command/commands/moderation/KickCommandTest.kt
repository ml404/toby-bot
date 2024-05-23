package toby.command.commands.moderation

import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.GuildVoiceState
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Mentions
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import toby.command.CommandContext
import toby.command.CommandTest

internal class KickCommandTest : CommandTest {
    private var kickCommand: KickCommand? = null

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        kickCommand = KickCommand()
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
    }

    @Test
    fun test_KickWithValidPermissions_kicksEveryoneInChannel() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        kickSetup(botKickOthers = true, memberKickOthers = true, mentionedMembers = listOf(CommandTest.targetMember))

        //Act
        kickCommand!!.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.event, Mockito.times(1)).guild
        Mockito.verify(CommandTest.guild, Mockito.times(1)).kick(CommandTest.targetMember)
    }

    @Test
    fun test_KickWithValidPermissionsAndMultipleMembers_kicksEveryoneInChannel() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        kickSetup(
            botKickOthers = true,
            memberKickOthers = true,
            mentionedMembers = listOf(CommandTest.member, CommandTest.targetMember)
        )

        //Act
        kickCommand!!.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.event, Mockito.times(1)).guild
        Mockito.verify(CommandTest.guild, Mockito.times(1)).kick(CommandTest.member)
        Mockito.verify(CommandTest.guild, Mockito.times(1)).kick(CommandTest.targetMember)
    }


    @Test
    fun test_KickWithInvalidBotPermissions_throwsError() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        kickSetup(botKickOthers = false, memberKickOthers = true, mentionedMembers = listOf(CommandTest.targetMember))

        //Act
        kickCommand!!.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.event, Mockito.times(1)).guild
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("I'm not allowed to kick %s"),
            ArgumentMatchers.eq(CommandTest.targetMember)
        )
    }

    @Test
    fun test_KickWithInvalidUserPermissions_throwsError() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        kickSetup(botKickOthers = true, memberKickOthers = false, mentionedMembers = listOf(CommandTest.targetMember))

        //Act
        kickCommand!!.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.event, Mockito.times(1)).guild
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("You can't kick %s"),
            ArgumentMatchers.eq(CommandTest.targetMember)
        )
    }

    companion object {
        private fun kickSetup(botKickOthers: Boolean, memberKickOthers: Boolean, mentionedMembers: List<Member>) {
            val audioChannelUnion = Mockito.mock(AudioChannelUnion::class.java)
            val guildVoiceState = Mockito.mock(GuildVoiceState::class.java)
            val auditableRestAction = Mockito.mock(AuditableRestAction::class.java)
            val optionMapping = Mockito.mock(OptionMapping::class.java)
            val mentions = Mockito.mock(Mentions::class.java)
            Mockito.`when`<OptionMapping>(CommandTest.event.getOption("users")).thenReturn(optionMapping)
            Mockito.`when`(optionMapping.mentions).thenReturn(mentions)
            Mockito.`when`(mentions.members).thenReturn(mentionedMembers)
            Mockito.`when`(
                CommandTest.member.canInteract(
                    ArgumentMatchers.any(
                        Member::class.java
                    )
                )
            ).thenReturn(true)
            Mockito.`when`(CommandTest.botMember.hasPermission(Permission.KICK_MEMBERS))
                .thenReturn(botKickOthers)
            Mockito.`when`(
                CommandTest.botMember.canInteract(
                    ArgumentMatchers.any(
                        Member::class.java
                    )
                )
            ).thenReturn(botKickOthers)
            Mockito.`when`<GuildVoiceState>(CommandTest.member.voiceState).thenReturn(guildVoiceState)
            Mockito.`when`(CommandTest.member.hasPermission(Permission.KICK_MEMBERS))
                .thenReturn(memberKickOthers)
            Mockito.`when`(guildVoiceState.channel).thenReturn(audioChannelUnion)
            Mockito.`when`<AuditableRestAction<Void>>(CommandTest.guild.kick(ArgumentMatchers.any()))
                .thenReturn(auditableRestAction as AuditableRestAction<Void>)
            Mockito.`when`(auditableRestAction.reason(ArgumentMatchers.eq("because you told me to.")))
                .thenReturn(auditableRestAction)
        }
    }
}