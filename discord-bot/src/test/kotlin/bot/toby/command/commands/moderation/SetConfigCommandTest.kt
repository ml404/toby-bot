package bot.toby.command.commands.moderation

import bot.toby.activity.ActivityTrackingNotifier
import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.member
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.DefaultCommandContext
import database.dto.ConfigDto
import database.dto.ConfigDto.Configurations.*
import database.service.ConfigService
import io.mockk.*
import net.dv8tion.jda.api.entities.channel.unions.GuildChannelUnion
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

internal class SetConfigCommandTest : CommandTest {
    lateinit var setConfigCommand: SetConfigCommand
    lateinit var configService: ConfigService
    lateinit var activityTrackingNotifier: ActivityTrackingNotifier

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        configService = mockk(relaxed = true)
        activityTrackingNotifier = mockk(relaxed = true)
        // Default upsert: report Created. Tests that need Updated semantics
        // (e.g. ACTIVITY_TRACKING first-enable) override per-test.
        every { configService.upsertConfig(any(), any(), any()) } answers {
            ConfigService.UpsertResult.Created(ConfigDto(firstArg(), secondArg(), thirdArg()))
        }
        setConfigCommand = SetConfigCommand(configService, activityTrackingNotifier)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearAllMocks()
    }

    @Test
    fun testSetConfig_notAsServerOwner_sendsErrorMessage() {
        val commandContext = DefaultCommandContext(event)
        val volumeOptionMapping = mockk<OptionMapping>()

        every { event.getOption(VOLUME.name.lowercase(Locale.getDefault())) } returns volumeOptionMapping
        every { volumeOptionMapping.asInt } returns 20
        every { volumeOptionMapping.name } returns VOLUME.name
        every { member.isOwner } returns false
        every { event.options } returns listOf(volumeOptionMapping)

        setConfigCommand.handle(commandContext, requestingUserDto, 0)

        verify(exactly = 0) { configService.upsertConfig(any(), any(), any()) }
        verify(exactly = 1) {
            event.hook.sendMessage("This is currently reserved for the owner of the server only, this may change in future")
        }
    }

    @Test
    fun testSetConfig_withOneConfig_writesViaUpsert() {
        val commandContext = DefaultCommandContext(event)
        val volumeOptionMapping = mockk<OptionMapping>()

        every { event.getOption(VOLUME.name.lowercase(Locale.getDefault())) } returns volumeOptionMapping
        every { volumeOptionMapping.asInt } returns 20
        every { volumeOptionMapping.name } returns VOLUME.name
        every { member.isOwner } returns true
        every { event.options } returns listOf(volumeOptionMapping)

        setConfigCommand.handle(commandContext, requestingUserDto, 0)

        verify(exactly = 1) { configService.upsertConfig(VOLUME.configValue, "20", "1") }
        verify(exactly = 1) { event.hook.sendMessage("Set default volume to '20'") }
    }

    @Test
    fun testSetConfig_updateBranch_isInvisibleToCallers() {
        // The whole point of upsertConfig: the caller doesn't branch on
        // exists-vs-not-exists. Verify a single call regardless of the
        // pre-existing row.
        val commandContext = DefaultCommandContext(event)
        val volumeOptionMapping = mockk<OptionMapping>()
        every { configService.upsertConfig(VOLUME.configValue, "20", "1") } returns
            ConfigService.UpsertResult.Updated(
                ConfigDto(VOLUME.configValue, "20", "1"),
                previousValue = "10"
            )

        every { event.getOption(VOLUME.name.lowercase(Locale.getDefault())) } returns volumeOptionMapping
        every { volumeOptionMapping.asInt } returns 20
        every { volumeOptionMapping.name } returns VOLUME.name
        every { member.isOwner } returns true
        every { event.options } returns listOf(volumeOptionMapping)

        setConfigCommand.handle(commandContext, requestingUserDto, 0)

        verify(exactly = 1) { configService.upsertConfig(VOLUME.configValue, "20", "1") }
        verify(exactly = 1) { event.hook.sendMessage("Set default volume to '20'") }
    }

    @Test
    fun testSetConfig_withDeleteDelay_writesViaUpsert() {
        val commandContext = DefaultCommandContext(event)
        val deleteDelayOptionMapping = mockk<OptionMapping>()

        every { event.getOption(DELETE_DELAY.name.lowercase(Locale.getDefault())) } returns deleteDelayOptionMapping
        every { deleteDelayOptionMapping.asInt } returns 20
        every { deleteDelayOptionMapping.name } returns DELETE_DELAY.name
        every { member.isOwner } returns true
        every { event.options } returns listOf(deleteDelayOptionMapping)

        setConfigCommand.handle(commandContext, requestingUserDto, 0)

        verify(exactly = 1) { configService.upsertConfig(DELETE_DELAY.configValue, "20", "1") }
        verify(exactly = 1) {
            event.hook.sendMessage(
                "Set default delete message delay for TobyBot music messages to '20' seconds"
            )
        }
    }

    @Test
    fun testSetConfig_withMultipleSpecified_writesTwoUpserts() {
        val commandContext = DefaultCommandContext(event)
        val deleteDelayOptionMapping = mockk<OptionMapping>()
        val volumeOptionMapping = mockk<OptionMapping>()

        every { event.member } returns member
        every { member.isOwner } returns true
        every { event.options } returns listOf(deleteDelayOptionMapping, volumeOptionMapping)
        every { deleteDelayOptionMapping.name } returns DELETE_DELAY.name.lowercase(Locale.getDefault())
        every { deleteDelayOptionMapping.asInt } returns 20
        every { volumeOptionMapping.name } returns VOLUME.name.lowercase(Locale.getDefault())
        every { volumeOptionMapping.asInt } returns 20
        every { event.guild } returns mockk {
            every { id } returns "1"
        }
        every { event.hook.sendMessage(any<String>()) } returns mockk {
            every { setEphemeral(any()) } returns this
            every { queue(any()) } just Runs
        }

        setConfigCommand.handle(commandContext, requestingUserDto, 0)

        verify(exactly = 1) { configService.upsertConfig(DELETE_DELAY.configValue, "20", "1") }
        verify(exactly = 1) { configService.upsertConfig(VOLUME.configValue, "20", "1") }
        verify(exactly = 2) {
            event.hook.sendMessage(any<String>())
        }
    }

    @Test
    fun testSetConfig_withMoveChannel_writesViaUpsert() {
        val commandContext = DefaultCommandContext(event)
        val moveOptionMapping = mockk<OptionMapping>()
        val guildChannelUnion = mockk<GuildChannelUnion>()

        every { event.getOption(MOVE.name.lowercase(Locale.getDefault())) } returns moveOptionMapping
        every { moveOptionMapping.asChannel } returns guildChannelUnion
        every { moveOptionMapping.name } returns MOVE.name.lowercase(Locale.getDefault())
        every { guildChannelUnion.name } returns "Channel Name"
        every { member.isOwner } returns true
        every { event.options } returns listOf(moveOptionMapping)

        setConfigCommand.handle(commandContext, requestingUserDto, 0)

        verify(exactly = 1) { configService.upsertConfig(MOVE.configValue, "Channel Name", "1") }
        verify(exactly = 1) {
            event.hook.sendMessage("Set default move channel to 'Channel Name'")
        }
    }

    @Test
    fun testSetConfig_activityTrackingFirstEnable_firesNotifierWhenPreviouslyDisabled() {
        // Updated outcome with previousValue="false" → notifier should fire.
        val commandContext = DefaultCommandContext(event)
        val optionMapping = mockk<OptionMapping>()
        val guildMock = mockk<net.dv8tion.jda.api.entities.Guild>(relaxed = true).also {
            every { it.id } returns "1"
            every { it.idLong } returns 1L
        }
        every { event.guild } returns guildMock
        every { event.member } returns member
        every { member.isOwner } returns true
        every { event.options } returns listOf(optionMapping)
        every { optionMapping.name } returns ACTIVITY_TRACKING.name.lowercase(Locale.getDefault())
        every { optionMapping.asBoolean } returns true
        every { configService.upsertConfig(ACTIVITY_TRACKING.configValue, "true", "1") } returns
            ConfigService.UpsertResult.Updated(
                ConfigDto(ACTIVITY_TRACKING.configValue, "true", "1"),
                previousValue = "false"
            )

        setConfigCommand.handle(commandContext, requestingUserDto, 0)

        verify(exactly = 1) { activityTrackingNotifier.notifyMembersOnFirstEnable(guildMock) }
    }

    @Test
    fun testSetConfig_activityTrackingAlreadyEnabled_doesNotReFireNotifier() {
        // Updated outcome with previousValue="true" → notifier already
        // ran historically, so don't fire again.
        val commandContext = DefaultCommandContext(event)
        val optionMapping = mockk<OptionMapping>()
        val guildMock = mockk<net.dv8tion.jda.api.entities.Guild>(relaxed = true).also {
            every { it.id } returns "1"
            every { it.idLong } returns 1L
        }
        every { event.guild } returns guildMock
        every { event.member } returns member
        every { member.isOwner } returns true
        every { event.options } returns listOf(optionMapping)
        every { optionMapping.name } returns ACTIVITY_TRACKING.name.lowercase(Locale.getDefault())
        every { optionMapping.asBoolean } returns true
        every { configService.upsertConfig(ACTIVITY_TRACKING.configValue, "true", "1") } returns
            ConfigService.UpsertResult.Updated(
                ConfigDto(ACTIVITY_TRACKING.configValue, "true", "1"),
                previousValue = "true"
            )

        setConfigCommand.handle(commandContext, requestingUserDto, 0)

        verify(exactly = 0) { activityTrackingNotifier.notifyMembersOnFirstEnable(any()) }
    }
}
