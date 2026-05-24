package bot.toby.button.buttons.team

import bot.toby.command.commands.misc.TeamCommand
import core.button.ButtonContext
import database.dto.guild.TeamSplitSessionDto
import database.dto.user.UserDto
import database.service.guild.TeamSplitSessionService
import database.service.guild.encodeAssignments
import database.service.guild.encodeTeamNames
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.requests.RestAction
import net.dv8tion.jda.api.requests.restaction.ChannelAction
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import bot.toby.button.buttons.team.TeamConfirmButton

class TeamConfirmButtonTest {

    private lateinit var sessionService: TeamSplitSessionService
    private lateinit var button: TeamConfirmButton
    private lateinit var ctx: ButtonContext
    private lateinit var event: ButtonInteractionEvent
    private lateinit var hook: InteractionHook
    private lateinit var guild: Guild
    private lateinit var requesterDto: UserDto

    private val sessionId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        sessionService = mockk(relaxed = true)
        button = TeamConfirmButton(sessionService)
        hook = mockk(relaxed = true)
        event = mockk(relaxed = true) {
            every { componentId } returns "${TeamCommand.BUTTON_CONFIRM}:$sessionId"
            every { deferEdit() } returns mockk(relaxed = true)
            every { this@mockk.hook } returns this@TeamConfirmButtonTest.hook
        }
        guild = mockk(relaxed = true) {
            every { maxBitrate } returns 96000
        }
        ctx = mockk {
            every { this@mockk.event } returns this@TeamConfirmButtonTest.event
            every { this@mockk.guild } returns this@TeamConfirmButtonTest.guild
        }
        requesterDto = mockk(relaxed = true)

        // JDA's fluent edit chain returns the self-typed `R` from the parent
        // interface (MessageEditRequest<R>), which is erased at runtime — mockk's
        // relaxed mode returns the base supertype's mock and the subsequent
        // `.setComponents` / `.queue` calls explode with a ClassCastException.
        // Stubbing each step to return the same typed mock fixes the chain.
        // Production only exercises the vararg overload of setComponents (it
        // passes a single ActionRow), so we stub just that — Kotlin can't
        // resolve `Collection<*>` against the typed `Collection<out
        // MessageTopLevelComponent>` overload anyway.
        @Suppress("UNCHECKED_CAST")
        val editAction = mockk<WebhookMessageEditAction<Message>>(relaxed = true)
        every { hook.editOriginal(any<String>()) } returns editAction
        every { hook.editOriginalEmbeds(any<MessageEmbed>(), *anyVararg<MessageEmbed>()) } returns editAction
        every { editAction.setEmbeds(any<Collection<MessageEmbed>>()) } returns editAction
        every { editAction.setComponents(*anyVararg<MessageTopLevelComponent>()) } returns editAction
        every { editAction.queue() } just Runs
    }

    @Test
    fun `name and defersReply match the contract the manager expects`() {
        // Manager dispatches by name prefix and gates its own deferReply on this flag.
        assertEquals(TeamCommand.BUTTON_CONFIRM, button.name)
        assert(!button.defersReply)
    }

    @Test
    fun `creates one voice channel per team and moves each resolved member`() {
        val (memberA, memberB, memberC) = listOf(111L, 222L, 333L).map { memberMock(it) }
        every { guild.getMemberById(111L) } returns memberA
        every { guild.getMemberById(222L) } returns memberB
        every { guild.getMemberById(333L) } returns memberC

        val sessionDto = TeamSplitSessionDto(
            id = sessionId, guildId = 100L, requesterDiscordId = 42L,
            memberIds = "111,222,333", teamCount = 2,
            assignments = encodeAssignments(listOf(listOf(111L, 222L), listOf(333L))),
            teamNames = encodeTeamNames(listOf("Red", "Blue")),
            lastAction = TeamSplitSessionDto.ACTION_CREATED,
        )
        every { sessionService.getSession(sessionId) } returns sessionDto

        val redChannel = voiceChannelMock("Red")
        val blueChannel = voiceChannelMock("Blue")
        val redCreate = channelActionMock(redChannel)
        val blueCreate = channelActionMock(blueChannel)
        every { guild.createVoiceChannel("Red") } returns redCreate
        every { guild.createVoiceChannel("Blue") } returns blueCreate

        val moveRest = mockk<RestAction<Void>>(relaxed = true)
        every { guild.moveVoiceMember(any(), any<AudioChannel>()) } returns moveRest
        every { moveRest.queue() } just Runs

        button.handle(ctx, requesterDto, 0)

        verify(exactly = 1) { guild.createVoiceChannel("Red") }
        verify(exactly = 1) { guild.createVoiceChannel("Blue") }
        verify(exactly = 3) { guild.moveVoiceMember(any(), any<AudioChannel>()) }
        verify(exactly = 1) { sessionService.markConfirmed(sessionId) }
    }

    @Test
    fun `second click on already-confirmed session creates no channels`() {
        val sessionDto = TeamSplitSessionDto(
            id = sessionId, guildId = 100L, requesterDiscordId = 42L,
            memberIds = "111,222", teamCount = 2,
            assignments = encodeAssignments(listOf(listOf(111L), listOf(222L))),
            teamNames = encodeTeamNames(listOf("A", "B")),
            lastAction = TeamSplitSessionDto.ACTION_CONFIRMED,
        )
        every { sessionService.getSession(sessionId) } returns sessionDto

        button.handle(ctx, requesterDto, 0)

        verify(exactly = 0) { guild.createVoiceChannel(any<String>()) }
        verify(exactly = 0) { sessionService.markConfirmed(any()) }
        verify { hook.sendMessage(match<String> { it.contains("already created", ignoreCase = true) }) }
    }

    @Test
    fun `expired session edits message in place rather than creating channels`() {
        every { sessionService.getSession(sessionId) } returns null

        button.handle(ctx, requesterDto, 0)

        verify(exactly = 0) { guild.createVoiceChannel(any<String>()) }
        verify { hook.editOriginal(any<String>()) }
    }

    private fun memberMock(id: Long): Member {
        val u = mockk<User>(relaxed = true) { every { isBot } returns false }
        return mockk(relaxed = true) {
            every { idLong } returns id
            every { user } returns u
            every { effectiveName } returns "Name $id"
        }
    }

    private fun voiceChannelMock(name: String): VoiceChannel = mockk(relaxed = true) {
        every { this@mockk.name } returns name
    }

    private fun channelActionMock(produces: VoiceChannel): ChannelAction<VoiceChannel> {
        val action = mockk<ChannelAction<VoiceChannel>>(relaxed = true)
        every { action.setBitrate(any()) } returns action
        // `complete()` is `RestAction<T>.complete()`. T is erased at runtime;
        // mockk needs a hint to fabricate a return value of the right concrete
        // type (otherwise it picks the base interface and the cast to
        // VoiceChannel in production blows up). Same pattern the legacy
        // TeamCommandTest used before the refactor.
        every { action.hint(VoiceChannel::class).complete() } returns produces
        return action
    }
}
