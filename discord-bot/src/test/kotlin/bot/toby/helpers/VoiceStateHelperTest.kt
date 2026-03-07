package bot.toby.helpers

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.CommandTest.Companion.member
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.CommandTest.Companion.webhookMessageCreateAction
import io.mockk.*
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.GuildVoiceState
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class VoiceStateHelperTest : CommandTest {

    private val target: Member = mockk(relaxed = true)
    private val voiceState: GuildVoiceState = mockk(relaxed = true)
    private val audioChannel: AudioChannelUnion = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        every { member.voiceState } returns voiceState
        every { voiceState.channel } returns audioChannel
        every { audioChannel.members } returns listOf(target)
        every { target.effectiveName } returns "TargetUser"
        every { webhookMessageCreateAction.queue(any()) } just Runs
        every { requestingUserDto.superUser } returns true
        every { member.canInteract(target) } returns true
        every { member.hasPermission(Permission.VOICE_MUTE_OTHERS) } returns true
        every { guild.selfMember.hasPermission(Permission.VOICE_MUTE_OTHERS) } returns true
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
    }

    @Test
    fun `muteOrUnmuteMembers does nothing when member voiceState channel is null`() {
        every { voiceState.channel } returns null

        VoiceStateHelper.muteOrUnmuteMembers(member, requestingUserDto, event, 0, guild, true)

        verify(exactly = 0) { event.hook.sendMessage(any<String>()) }
        verify(exactly = 0) { guild.mute(any(), any()) }
    }

    @Test
    fun `muteOrUnmuteMembers sends error when member cannot interact with target`() {
        every { member.canInteract(target) } returns false

        VoiceStateHelper.muteOrUnmuteMembers(member, requestingUserDto, event, 0, guild, true)

        verify(exactly = 1) { event.hook.sendMessage(match<String> { it.contains("aren't allowed to mute") }) }
        verify(exactly = 0) { guild.mute(any(), any()) }
    }

    @Test
    fun `muteOrUnmuteMembers sends error when member lacks VOICE_MUTE_OTHERS permission`() {
        every { member.hasPermission(Permission.VOICE_MUTE_OTHERS) } returns false

        VoiceStateHelper.muteOrUnmuteMembers(member, requestingUserDto, event, 0, guild, true)

        verify(exactly = 1) { event.hook.sendMessage(match<String> { it.contains("aren't allowed to mute") }) }
        verify(exactly = 0) { guild.mute(any(), any()) }
    }

    @Test
    fun `muteOrUnmuteMembers sends error when requesting user is not superUser`() {
        every { requestingUserDto.superUser } returns false

        VoiceStateHelper.muteOrUnmuteMembers(member, requestingUserDto, event, 0, guild, true)

        verify(exactly = 1) { event.hook.sendMessage(match<String> { it.contains("aren't allowed to mute") }) }
        verify(exactly = 0) { guild.mute(any(), any()) }
    }

    @Test
    fun `muteOrUnmuteMembers sends error when bot lacks VOICE_MUTE_OTHERS permission`() {
        every { guild.selfMember.hasPermission(Permission.VOICE_MUTE_OTHERS) } returns false

        VoiceStateHelper.muteOrUnmuteMembers(member, requestingUserDto, event, 0, guild, true)

        verify(exactly = 1) { event.hook.sendMessage(match<String> { it.contains("not allowed to mute") }) }
        verify(exactly = 0) { guild.mute(any(), any()) }
    }

    @Test
    fun `muteOrUnmuteMembers mutes member when all permissions are satisfied`() {
        val muteAction = mockk<net.dv8tion.jda.api.requests.restaction.AuditableRestAction<Void>>(relaxed = true)
        every { guild.mute(target, true) } returns muteAction
        every { muteAction.reason(any()) } returns muteAction
        every { muteAction.queue() } just Runs

        VoiceStateHelper.muteOrUnmuteMembers(member, requestingUserDto, event, 0, guild, true)

        verify(exactly = 1) { guild.mute(target, true) }
    }

    @Test
    fun `muteOrUnmuteMembers unmutes member when muteTargets is false`() {
        val muteAction = mockk<net.dv8tion.jda.api.requests.restaction.AuditableRestAction<Void>>(relaxed = true)
        every { guild.mute(target, false) } returns muteAction
        every { muteAction.reason(any()) } returns muteAction
        every { muteAction.queue() } just Runs

        VoiceStateHelper.muteOrUnmuteMembers(member, requestingUserDto, event, 0, guild, false)

        verify(exactly = 1) { guild.mute(target, false) }
    }

    @Test
    fun `muteOrUnmuteMembers uses unmute wording when muteTargets is false`() {
        every { member.canInteract(target) } returns false

        VoiceStateHelper.muteOrUnmuteMembers(member, requestingUserDto, event, 0, guild, false)

        verify(exactly = 1) { event.hook.sendMessage(match<String> { it.contains("aren't allowed to unmute") }) }
    }
}
