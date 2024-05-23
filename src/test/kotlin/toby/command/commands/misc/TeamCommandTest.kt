package toby.command.commands.misc

import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Mentions
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.requests.RestAction
import net.dv8tion.jda.api.requests.restaction.ChannelAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import toby.command.CommandContext
import toby.command.CommandTest
import toby.jpa.dto.UserDto

internal class TeamCommandTest : CommandTest {
    private var teamCommand: TeamCommand? = null

    @BeforeEach
    fun beforeEach() {
        setUpCommonMocks() // Initialize the mocks
        teamCommand = TeamCommand()
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
    }

    @Test
    fun testHandle_WithNoArgs() {
        // You can set up your test scenario here, including mocking event and UserDto.
        // Example:
        val requestingUserDto = userDto // You can set the user as needed
        val deleteDelay = 0

        // Create a CommandContext
        val ctx = CommandContext(CommandTest.event)

        // Test the handle method
        teamCommand!!.handle(ctx, requestingUserDto, deleteDelay)

        Mockito.verify(CommandTest.event, Mockito.times(1)).deferReply()
        Mockito.verify(CommandTest.event.hook)
            .sendMessage("Return X teams from a list of tagged users.")
    }

    @Test
    fun testHandle_WithArgs() {
        // You can set up your test scenario here, including mocking event and UserDto.
        val membersOption = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("members")).thenReturn(membersOption)
        Mockito.`when`(membersOption.asString).thenReturn("user1, user2")

        val sizeOption = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("size")).thenReturn(sizeOption)
        Mockito.`when`(sizeOption.asInt).thenReturn(2)

        Mockito.`when`<List<OptionMapping>>(CommandTest.event.options)
            .thenReturn(listOf(membersOption, sizeOption))
        val voiceChannel = Mockito.mock(ChannelAction::class.java) as ChannelAction<VoiceChannel>
        Mockito.`when`<ChannelAction<VoiceChannel>>(CommandTest.guild.createVoiceChannel(ArgumentMatchers.anyString()))
            .thenReturn(voiceChannel)
        Mockito.`when`(voiceChannel.setBitrate(ArgumentMatchers.anyInt())).thenReturn(voiceChannel)
        val createdVoiceChannel = Mockito.mock(
            VoiceChannel::class.java
        )
        Mockito.`when`(voiceChannel.complete()).thenReturn(createdVoiceChannel)
        Mockito.`when`(createdVoiceChannel.name).thenReturn("channelName")
        val deleteDelay = 0

        // Mock the guild.moveVoiceMember() method
        val mentions = Mockito.mock(Mentions::class.java)
        Mockito.`when`(CommandTest.event.getOption("members")!!.mentions).thenReturn(mentions)
        val mockMember1 = Mockito.mock(Member::class.java)
        val mockMember2 = Mockito.mock(Member::class.java)
        val memberList: ArrayList<Member?> = ArrayList(listOf(mockMember1, mockMember2))
        Mockito.`when`(mockMember1.effectiveName).thenReturn("Name 1")
        Mockito.`when`(mockMember2.effectiveName).thenReturn("Name 2")
        Mockito.`when`(mentions.members).thenReturn(memberList)

        guildMoveVoiceMemberMocking(createdVoiceChannel, mockMember1)
        guildMoveVoiceMemberMocking(createdVoiceChannel, mockMember2)

        val requestingUserDto = userDto // You can set the user as needed

        // Create a CommandContext
        val ctx = CommandContext(CommandTest.event)

        // Test the handle method
        teamCommand!!.handle(ctx, requestingUserDto, deleteDelay)

        Mockito.verify(CommandTest.event, Mockito.times(1)).deferReply()
        Mockito.verify(CommandTest.interactionHook, Mockito.times(2)).sendMessageFormat(
            ArgumentMatchers.eq("Moved %s to '%s'"),
            ArgumentMatchers.anyString(),
            ArgumentMatchers.anyString()
        )
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessage(ArgumentMatchers.anyString())
    }

    companion object {
        private val userDto: UserDto
            get() = UserDto(1L, 1L,
                superUser = true,
                musicPermission = true,
                digPermission = true,
                memePermission = true,
                socialCredit = 0L,
                musicDto = null
            )

        private fun guildMoveVoiceMemberMocking(createdVoiceChannel: VoiceChannel, member: Member) {
            Mockito.`when`<RestAction<Void>>(
                CommandTest.guild.moveVoiceMember(
                    ArgumentMatchers.eq(
                        member
                    ), ArgumentMatchers.any<AudioChannel>()
                )
            ).thenAnswer {
                // Simulate the move
                CommandTest.event.hook
                    .sendMessageFormat("Moved %s to '%s'", member.effectiveName, createdVoiceChannel.name).complete()
                Mockito.mock(RestAction::class.java)
            }
        }
    }
}