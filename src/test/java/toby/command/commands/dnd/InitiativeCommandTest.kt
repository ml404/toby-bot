package toby.command.commands.dnd

import net.dv8tion.jda.api.entities.*
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion
import net.dv8tion.jda.api.entities.channel.unions.GuildChannelUnion
import net.dv8tion.jda.api.interactions.Interaction
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.kotlin.anyVararg
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.CommandTest.Companion.interactionHook
import toby.command.CommandTest.Companion.member
import toby.command.CommandTest.Companion.user
import toby.command.CommandTest.Companion.webhookMessageCreateAction
import toby.jpa.service.IUserService
import toby.jpa.service.impl.UserServiceImpl

internal class InitiativeCommandTest : CommandTest {
    private lateinit var initiativeCommand: InitiativeCommand

    @Mock
    lateinit var userService: IUserService

    @BeforeEach
    fun setup() {
        setUpCommonMocks()
        userService = Mockito.mock(UserServiceImpl::class.java)
        initiativeCommand = InitiativeCommand(userService)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
    }

    @Test
    fun test_initiativeCommandWithCorrectSetup_WithMembers() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val channelOptionMapping = Mockito.mock(OptionMapping::class.java)
        val dmOptionMapping = Mockito.mock(OptionMapping::class.java)
        val guildChannelUnion = Mockito.mock(GuildChannelUnion::class.java)
        val audioChannel = Mockito.mock(AudioChannel::class.java)
        val dmMember = Mockito.mock(Member::class.java)
        val interaction = Mockito.mock(
            Interaction::class.java
        )
        Mockito.`when`(channelOptionMapping.asChannel).thenReturn(guildChannelUnion)
        Mockito.`when`(dmOptionMapping.asMember).thenReturn(dmMember)
        Mockito.`when`(guildChannelUnion.asAudioChannel()).thenReturn(audioChannel)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("channel")).thenReturn(channelOptionMapping)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("dm")).thenReturn(dmOptionMapping)
        Mockito.`when`<List<Member>>(audioChannel.members).thenReturn(listOf(member))
        Mockito.`when`(interactionHook.interaction).thenReturn(interaction)
        Mockito.`when`<Guild?>(interaction.guild).thenReturn(CommandTest.guild)
        Mockito.`when`<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction.setActionRow(
                any(
                    Button::class.java
                ), any(
                    Button::class.java
                ), any(
                    Button::class.java
                )
            ) as WebhookMessageCreateAction<Message>?
        ).thenReturn(webhookMessageCreateAction)
        Mockito.`when`(member.user).thenReturn(user)


        //Act
        initiativeCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.event, Mockito.times(1)).deferReply()
        Mockito.verify(interactionHook, Mockito.times(1)).sendMessageEmbeds(
            any(), anyVararg()
        )
        Mockito.verify<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction, Mockito.times(1)
        ).setActionRow(
            any(), any(), any()
        )
    }

    @Test
    fun test_initiativeCommandWithCorrectSetup_WithNames() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val namesMapping = Mockito.mock(OptionMapping::class.java)
        val dmOptionMapping = Mockito.mock(OptionMapping::class.java)
        val dmMember = Mockito.mock(Member::class.java)
        val interaction = Mockito.mock(
            Interaction::class.java
        )
        Mockito.`when`(namesMapping.asString).thenReturn("name1, name2, name3, name4")
        Mockito.`when`(dmOptionMapping.asMember).thenReturn(dmMember)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("names")).thenReturn(namesMapping)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("dm")).thenReturn(dmOptionMapping)
        Mockito.`when`(interactionHook.interaction).thenReturn(interaction)
        Mockito.`when`<Guild?>(interaction.guild).thenReturn(CommandTest.guild)
        Mockito.`when`<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction.setActionRow(
                any(
                    Button::class.java
                ), any(
                    Button::class.java
                ), any(
                    Button::class.java
                )
            ) as WebhookMessageCreateAction<Message>?
        ).thenReturn(webhookMessageCreateAction)
        Mockito.`when`(member.user).thenReturn(user)


        //Act
        initiativeCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.event, Mockito.times(1)).deferReply()
        Mockito.verify(interactionHook, Mockito.times(1)).sendMessageEmbeds(any<MessageEmbed>())
        Mockito.verify<WebhookMessageCreateAction<Message>>(webhookMessageCreateAction, Mockito.times(1))
            .setActionRow(
                any(),
                any(),
                any()
            )
    }

    @Test
    fun test_initiativeCommandWithCorrectSetup_UsingMemberVoiceState() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val dmOptionMapping = Mockito.mock(OptionMapping::class.java)
        val guildChannelUnion = Mockito.mock(GuildChannelUnion::class.java)
        val audioChannel = Mockito.mock(AudioChannel::class.java)
        val dmMember = Mockito.mock(Member::class.java)
        val interaction = Mockito.mock(
            Interaction::class.java
        )
        val guildVoiceState = Mockito.mock(GuildVoiceState::class.java)
        val audioChannelUnion = Mockito.mock(AudioChannelUnion::class.java)
        Mockito.`when`(dmOptionMapping.asMember).thenReturn(dmMember)
        Mockito.`when`(guildChannelUnion.asAudioChannel()).thenReturn(audioChannel)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("dm")).thenReturn(dmOptionMapping)
        Mockito.`when`<GuildVoiceState>(member.voiceState).thenReturn(guildVoiceState)
        Mockito.`when`(guildVoiceState.channel).thenReturn(audioChannelUnion)
        Mockito.`when`<List<Member>>(audioChannelUnion.members).thenReturn(listOf(member))
        Mockito.`when`(interactionHook.interaction).thenReturn(interaction)
        Mockito.`when`<Guild?>(interaction.guild).thenReturn(CommandTest.guild)
        Mockito.`when`<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction.setActionRow(
                any(
                    Button::class.java
                ), any(
                    Button::class.java
                ), any(
                    Button::class.java
                )
            ) as WebhookMessageCreateAction<Message>?
        ).thenReturn(webhookMessageCreateAction as WebhookMessageCreateAction<Message>)
        Mockito.`when`(member.user).thenReturn(user)

        //Act
        initiativeCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.event, Mockito.times(1)).deferReply()
        Mockito.verify(interactionHook, Mockito.times(1)).sendMessageEmbeds(any<MessageEmbed>())
        Mockito.verify<WebhookMessageCreateAction<Message>>(webhookMessageCreateAction, Mockito.times(1)
        ).setActionRow(
            any(), any(), any()
        )
    }

    @Test
    fun test_initiativeCommandWithNoValidChannel() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val dmOptionMapping = Mockito.mock(OptionMapping::class.java)
        val audioChannel = Mockito.mock(AudioChannel::class.java)
        val dmMember = Mockito.mock(Member::class.java)
        val interaction = Mockito.mock(
            Interaction::class.java
        )
        Mockito.`when`(dmOptionMapping.asMember).thenReturn(dmMember)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("dm")).thenReturn(dmOptionMapping)
        Mockito.`when`<List<Member>>(audioChannel.members).thenReturn(listOf(member))
        Mockito.`when`(interactionHook.interaction).thenReturn(interaction)
        Mockito.`when`<Guild?>(interaction.guild).thenReturn(CommandTest.guild)
        Mockito.`when`(CommandTest.event.reply(ArgumentMatchers.anyString()))
            .thenReturn(CommandTest.replyCallbackAction)
        Mockito.`when`(CommandTest.replyCallbackAction.setEphemeral(true)).thenReturn(CommandTest.replyCallbackAction)

        //Act
        initiativeCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.event, Mockito.times(1)).deferReply()
        Mockito.verify(interactionHook, Mockito.times(0)).sendMessageEmbeds(any(), anyVararg())
        Mockito.verify(
            webhookMessageCreateAction as WebhookMessageCreateAction<Message>, Mockito.times(0)
        ).setActionRow(
            any(), any(), any()
        )
        Mockito.verify(CommandTest.event, Mockito.times(1))
            .reply("You must either be in a voice channel when using this command, or tag a voice channel in the channel option with people in it, or give a list of names to roll for.")
    }

    @Test
    fun test_initiativeCommandWithNoNonDMMembersAndAValidChannelOption() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val channelOptionMapping = Mockito.mock(OptionMapping::class.java)
        val dmOptionMapping = Mockito.mock(OptionMapping::class.java)
        val guildChannelUnion = Mockito.mock(GuildChannelUnion::class.java)
        val audioChannel = Mockito.mock(AudioChannel::class.java)
        val dmMember = Mockito.mock(Member::class.java)
        val interaction = Mockito.mock(
            Interaction::class.java
        )
        Mockito.`when`(channelOptionMapping.asChannel).thenReturn(guildChannelUnion)
        Mockito.`when`(dmOptionMapping.asMember).thenReturn(dmMember)
        Mockito.`when`(guildChannelUnion.asAudioChannel()).thenReturn(audioChannel)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("channel")).thenReturn(channelOptionMapping)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("dm")).thenReturn(dmOptionMapping)
        Mockito.`when`(audioChannel.members).thenReturn(listOf(dmMember))
        Mockito.`when`(interactionHook.interaction).thenReturn(interaction)
        Mockito.`when`<Guild?>(interaction.guild).thenReturn(CommandTest.guild)
        Mockito.`when`(CommandTest.event.reply(ArgumentMatchers.anyString()))
            .thenReturn(CommandTest.replyCallbackAction)
        Mockito.`when`(CommandTest.replyCallbackAction.setEphemeral(true)).thenReturn(CommandTest.replyCallbackAction)

        //Act
        initiativeCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.event, Mockito.times(1)).deferReply()
        Mockito.verify(interactionHook, Mockito.times(0)).sendMessageEmbeds(
            any(), anyVararg()

        )
        Mockito.verify<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction, Mockito.times(0)
        ).setActionRow(
            any(), any(), any()
        )
        Mockito.verify(CommandTest.event, Mockito.times(1))
            .reply("The amount of non DM members in the voice channel you're in, or the one you mentioned, is empty, so no rolls were done.")
    }
}