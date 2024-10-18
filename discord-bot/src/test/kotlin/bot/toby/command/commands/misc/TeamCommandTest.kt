package bot.toby.command.commands.misc

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.DefaultCommandContext
import io.mockk.*
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

internal class TeamCommandTest : CommandTest {
    private lateinit var teamCommand: TeamCommand

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
        // Set up your test scenario here, including mocking event and UserDto
        val deleteDelay = 0

        // Create a CommandContext
        val ctx = DefaultCommandContext(event)

        // Test the handle method
        teamCommand.handle(ctx, requestingUserDto, deleteDelay)

        verify(exactly = 1) { event.deferReply() }
        verify {
            event.hook.sendMessage("Return X teams from a list of tagged users.")
        }
    }

    @Test
    fun testHandle_WithArgs() {
        // Set up your test scenario here, including mocking event and UserDto
        val membersOption = mockk<OptionMapping>()
        val cleanupOption = mockk<OptionMapping>()
        every { event.getOption("members") } returns membersOption
        every { event.getOption("cleanup") } returns cleanupOption
        every { membersOption.asString } returns "user1, user2"
        every { cleanupOption.asBoolean } returns false

        val sizeOption = mockk<OptionMapping>()
        every { event.getOption("size") } returns sizeOption
        every { sizeOption.asInt } returns 2

        every { event.options } returns listOf(membersOption, sizeOption)

        val createdVoiceChannel = mockk<VoiceChannel>()
        val voiceChannelCreation = mockk<ChannelAction<VoiceChannel>>()
        every { guild.createVoiceChannel(any()) } returns voiceChannelCreation

        val voiceChannelModification = mockk<ChannelAction<VoiceChannel>>()
        every { voiceChannelCreation.setBitrate(any()) } returns voiceChannelModification

        every { voiceChannelModification.hint(VoiceChannel::class).complete() } returns createdVoiceChannel
        every { createdVoiceChannel.name } returns "channelName"

        val deleteDelay = 0

        // Mock the guild.moveVoiceMember() method
        val mentions = mockk<Mentions>()
        every { event.getOption("members")?.mentions } returns mentions

        val mockMember1 = mockk<Member>()
        val mockMember2 = mockk<Member>()
        val memberList = arrayListOf(mockMember1, mockMember2)
        every { mockMember1.effectiveName } returns "Name 1"
        every { mockMember2.effectiveName } returns "Name 2"
        every { mentions.members } returns memberList

        guildMoveVoiceMemberMocking(createdVoiceChannel, mockMember1)
        guildMoveVoiceMemberMocking(createdVoiceChannel, mockMember2)

        // Create a CommandContext
        val ctx = DefaultCommandContext(event)

        // Test the handle method
        teamCommand.handle(ctx, requestingUserDto, deleteDelay)

        verify(exactly = 1) { event.deferReply() }
        verify(exactly = 1) { event.hook.sendMessage(any<String>()) }
    }

    companion object {
        private fun guildMoveVoiceMemberMocking(createdVoiceChannel: VoiceChannel, member: Member) {
            val restAction = mockk<RestAction<Void>>()
            every {
                guild.moveVoiceMember(member, createdVoiceChannel as AudioChannel)
            } returns restAction

            every { restAction.queue() } just Runs

        }
    }
}
