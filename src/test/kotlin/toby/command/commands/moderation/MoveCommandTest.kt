package toby.command.commands.moderation

import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.GuildVoiceState
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Mentions
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion
import net.dv8tion.jda.api.entities.channel.unions.GuildChannelUnion
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.requests.RestAction
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito
import toby.command.CommandContext
import toby.command.CommandTest
import toby.jpa.dto.ConfigDto
import toby.jpa.service.IConfigService

internal class MoveCommandTest : CommandTest {
    private var moveCommand: MoveCommand? = null

    @Mock
    lateinit var configService: IConfigService

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        configService = Mockito.mock(IConfigService::class.java)
        Mockito.`when`(configService.getConfigByName(ConfigDto.Configurations.MOVE.configValue, "1"))
            .thenReturn(ConfigDto(ConfigDto.Configurations.MOVE.configValue, "CHANNEL", "1"))
        moveCommand = MoveCommand(configService)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        Mockito.reset(configService)
    }

    @Test
    fun test_moveWithValidPermissions_movesEveryoneInChannel() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        moveSetup(botMoveOthers = true, memberMoveOthers = true, mentionedMembers = listOf(CommandTest.targetMember))

        //Act
        moveCommand!!.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.event, Mockito.times(1)).guild
        Mockito.verify(CommandTest.guild, Mockito.times(1)).moveVoiceMember(
            ArgumentMatchers.eq(CommandTest.targetMember),
            ArgumentMatchers.any<AudioChannel>()
        )
    }

    @Test
    fun test_moveWithValidPermissionsAndMultipleMembers_movesEveryoneInChannel() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        moveSetup(true,
            memberMoveOthers = true,
            mentionedMembers = listOf(CommandTest.member, CommandTest.targetMember)
        )

        //Act
        moveCommand!!.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.event, Mockito.times(1)).guild
        Mockito.verify(CommandTest.guild, Mockito.times(1)).moveVoiceMember(
            ArgumentMatchers.eq(CommandTest.member),
            ArgumentMatchers.any<AudioChannel>()
        )
        Mockito.verify(CommandTest.guild, Mockito.times(1)).moveVoiceMember(
            ArgumentMatchers.eq(CommandTest.targetMember),
            ArgumentMatchers.any<AudioChannel>()
        )
    }


    @Test
    fun test_moveWithInvalidBotPermissions_throwsError() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        moveSetup(botMoveOthers = false, memberMoveOthers = true, mentionedMembers = listOf(CommandTest.targetMember))
        val guildVoiceState = Mockito.mock(GuildVoiceState::class.java)
        Mockito.`when`<GuildVoiceState>(CommandTest.targetMember.voiceState).thenReturn(guildVoiceState)
        Mockito.`when`(guildVoiceState.inAudioChannel()).thenReturn(true)

        //Act
        moveCommand!!.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.event, Mockito.times(1)).guild
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("I'm not allowed to move %s"),
            ArgumentMatchers.eq("Target Effective Name")
        )
    }

    @Test
    fun test_moveWithInvalidUserPermissions_throwsError() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        moveSetup(botMoveOthers = true, memberMoveOthers = false, mentionedMembers = listOf(CommandTest.targetMember))

        //Act
        moveCommand!!.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.event, Mockito.times(1)).guild
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("You can't move '%s'"),
            ArgumentMatchers.eq("Target Effective Name")
        )
    }

    @Test
    fun test_moveWithUserNotInChannel_throwsError() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        moveSetup(botMoveOthers = true, memberMoveOthers = true, mentionedMembers = listOf(CommandTest.targetMember))
        val guildVoiceState = Mockito.mock(GuildVoiceState::class.java)
        Mockito.`when`<GuildVoiceState>(CommandTest.targetMember.voiceState).thenReturn(guildVoiceState)
        Mockito.`when`(guildVoiceState.inAudioChannel()).thenReturn(false)

        //Act
        moveCommand!!.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.event, Mockito.times(1)).guild
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("Mentioned user '%s' is not connected to a voice channel currently, so cannot be moved."),
            ArgumentMatchers.eq("Target Effective Name")
        )
    }

    companion object {
        private fun moveSetup(botMoveOthers: Boolean, memberMoveOthers: Boolean, mentionedMembers: List<Member>) {
            val audioChannelUnion = Mockito.mock(AudioChannelUnion::class.java)
            val guildVoiceState = Mockito.mock(GuildVoiceState::class.java)
            val auditableRestAction = Mockito.mock(AuditableRestAction::class.java)
            val channelOptionMapping = Mockito.mock(OptionMapping::class.java)
            val userOptionMapping = Mockito.mock(OptionMapping::class.java)
            val mentions = Mockito.mock(Mentions::class.java)

            Mockito.`when`<GuildVoiceState>(CommandTest.targetMember.voiceState)
                .thenReturn(guildVoiceState)
            Mockito.`when`(guildVoiceState.inAudioChannel()).thenReturn(true)
            Mockito.`when`<OptionMapping>(CommandTest.event.getOption("channel"))
                .thenReturn(channelOptionMapping)
            Mockito.`when`<OptionMapping>(CommandTest.event.getOption("users")).thenReturn(userOptionMapping)
            Mockito.`when`(userOptionMapping.mentions).thenReturn(mentions)
            val guildChannelUnion = Mockito.mock(GuildChannelUnion::class.java)
            Mockito.`when`(channelOptionMapping.asChannel).thenReturn(guildChannelUnion)
            Mockito.`when`(guildChannelUnion.name).thenReturn("Channel")
            Mockito.`when`(guildChannelUnion.asVoiceChannel()).thenReturn(
                Mockito.mock(
                    VoiceChannel::class.java
                )
            )
            Mockito.`when`(mentions.members).thenReturn(mentionedMembers)
            Mockito.`when`(
                CommandTest.member.canInteract(
                    ArgumentMatchers.any(
                        Member::class.java
                    )
                )
            ).thenReturn(true)
            Mockito.`when`(CommandTest.botMember.hasPermission(Permission.VOICE_MOVE_OTHERS))
                .thenReturn(botMoveOthers)
            Mockito.`when`(
                CommandTest.botMember.canInteract(
                    ArgumentMatchers.any(
                        Member::class.java
                    )
                )
            ).thenReturn(botMoveOthers)
            Mockito.`when`<GuildVoiceState>(CommandTest.member.voiceState).thenReturn(guildVoiceState)
            Mockito.`when`(CommandTest.member.hasPermission(Permission.VOICE_MOVE_OTHERS))
                .thenReturn(memberMoveOthers)
            Mockito.`when`(guildVoiceState.channel).thenReturn(audioChannelUnion)
            val voiceChannel = Mockito.mock(
                VoiceChannel::class.java
            )
            Mockito.`when`<List<VoiceChannel>>(CommandTest.guild.getVoiceChannelsByName("CHANNEL", true))
                .thenReturn(listOf(voiceChannel))
            Mockito.`when`<RestAction<Void>>(
                CommandTest.guild.moveVoiceMember(
                    ArgumentMatchers.any(),
                    ArgumentMatchers.any<AudioChannel>()
                )
            ).thenReturn(auditableRestAction as RestAction<Void>)
            Mockito.`when`(auditableRestAction.reason(ArgumentMatchers.any())).thenReturn(auditableRestAction)
        }
    }
}