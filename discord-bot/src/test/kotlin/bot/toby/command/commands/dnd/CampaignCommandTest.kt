package bot.toby.command.commands.dnd

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.CommandTest.Companion.member
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.CommandTest.Companion.webhookMessageCreateAction
import bot.toby.command.DefaultCommandContext
import bot.toby.helpers.UserDtoHelper
import database.dto.CampaignDto
import database.dto.CampaignPlayerDto
import database.dto.CampaignPlayerId
import database.service.CampaignPlayerService
import database.service.CampaignService
import io.mockk.*
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class CampaignCommandTest : CommandTest {

    private lateinit var campaignService: CampaignService
    private lateinit var campaignPlayerService: CampaignPlayerService
    private lateinit var userDtoHelper: UserDtoHelper
    private lateinit var sessionLog: web.service.SessionLogPublisher
    private lateinit var command: CampaignCommand

    private val guildId = 1L
    private val dmDiscordId = 1L
    private val playerDiscordId = 2L

    private fun makeCampaign(id: Long = 10L) = CampaignDto(
        id = id,
        guildId = guildId,
        channelId = guildId,
        dmDiscordId = dmDiscordId,
        name = "Test Campaign"
    )

    @BeforeEach
    fun setup() {
        setUpCommonMocks()
        campaignService = mockk(relaxed = true)
        campaignPlayerService = mockk(relaxed = true)
        userDtoHelper = mockk(relaxed = true)
        sessionLog = mockk(relaxed = true)
        command = CampaignCommand(campaignService, campaignPlayerService, userDtoHelper, sessionLog)

        every { event.subcommandName } returns "status"
        every { userDtoHelper.calculateUserDto(any(), any(), any()) } returns requestingUserDto
        every { requestingUserDto.discordId } returns dmDiscordId
        every { requestingUserDto.dndBeyondCharacterId } returns null
        every { requestingUserDto.superUser } returns false
        every { guild.getMemberById(any<Long>()) } returns member
        every { event.channel.idLong } returns guildId
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearAllMocks()
    }

    // create

    @Test
    fun `create - replies with campaign embed when no active campaign exists`() {
        every { event.subcommandName } returns "create"
        every { campaignService.getActiveCampaignForGuild(guildId) } returns null
        every { campaignService.createCampaign(any()) } answers { firstArg<CampaignDto>().apply { id = 10L } }

        val nameOption = mockk<OptionMapping> { every { asString } returns "Test Campaign" }
        every { event.getOption("name") } returns nameOption
        every { event.getOption("channel") } returns null

        command.handle(DefaultCommandContext(event), requestingUserDto, 5)

        verify { event.hook.sendMessageEmbeds(any<MessageEmbed>(), *anyVararg()) }
    }

    @Test
    fun `create - rejects when active campaign already exists`() {
        every { event.subcommandName } returns "create"
        every { campaignService.getActiveCampaignForGuild(guildId) } returns makeCampaign()

        command.handle(DefaultCommandContext(event), requestingUserDto, 5)

        verify { event.hook.sendMessage(match<String> { it.contains("already an active campaign") }) }
    }

    // join

    @Test
    fun `join - adds player to active campaign`() {
        every { event.subcommandName } returns "join"
        every { requestingUserDto.discordId } returns playerDiscordId
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { campaignPlayerService.getPlayer(any()) } returns null
        every { campaignPlayerService.addPlayer(any()) } answers { firstArg() }

        command.handle(DefaultCommandContext(event), requestingUserDto, 5)

        verify { campaignPlayerService.addPlayer(any()) }
        verify { event.hook.sendMessageEmbeds(any<MessageEmbed>(), *anyVararg()) }
    }

    @Test
    fun `join - rejects when no active campaign`() {
        every { event.subcommandName } returns "join"
        every { campaignService.getActiveCampaignForGuild(guildId) } returns null

        command.handle(DefaultCommandContext(event), requestingUserDto, 5)

        verify { event.hook.sendMessage(match<String> { it.contains("no active campaign") }) }
    }

    @Test
    fun `join - rejects when caller is the DM`() {
        every { event.subcommandName } returns "join"
        every { requestingUserDto.discordId } returns dmDiscordId
        every { campaignService.getActiveCampaignForGuild(guildId) } returns makeCampaign()

        command.handle(DefaultCommandContext(event), requestingUserDto, 5)

        verify { event.hook.sendMessage(match<String> { it.contains("DM") }) }
    }

    @Test
    fun `join - rejects when player already in campaign`() {
        every { event.subcommandName } returns "join"
        every { requestingUserDto.discordId } returns playerDiscordId
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { campaignPlayerService.getPlayer(CampaignPlayerId(campaign.id, playerDiscordId)) } returns mockk()

        command.handle(DefaultCommandContext(event), requestingUserDto, 5)

        verify { event.hook.sendMessage(match<String> { it.contains("already in") }) }
    }

    // leave

    @Test
    fun `leave - removes player from campaign`() {
        every { event.subcommandName } returns "leave"
        every { requestingUserDto.discordId } returns playerDiscordId
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { campaignPlayerService.getPlayer(CampaignPlayerId(campaign.id, playerDiscordId)) } returns mockk()
        every { campaignPlayerService.removePlayer(any()) } just Runs

        command.handle(DefaultCommandContext(event), requestingUserDto, 5)

        verify { campaignPlayerService.removePlayer(any()) }
        verify { event.hook.sendMessage(match<String> { it.contains("left the campaign") }) }
    }

    @Test
    fun `leave - rejects when player not in campaign`() {
        every { event.subcommandName } returns "leave"
        every { requestingUserDto.discordId } returns playerDiscordId
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { campaignPlayerService.getPlayer(any()) } returns null

        command.handle(DefaultCommandContext(event), requestingUserDto, 5)

        verify { event.hook.sendMessage(match<String> { it.contains("not in this campaign") }) }
    }

    // status

    @Test
    fun `status - shows campaign embed with players`() {
        every { event.subcommandName } returns "status"
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { campaignPlayerService.getPlayersForCampaign(campaign.id) } returns listOf(
            CampaignPlayerDto(id = CampaignPlayerId(campaign.id, playerDiscordId), guildId = guildId)
        )

        command.handle(DefaultCommandContext(event), requestingUserDto, 5)

        verify { event.hook.sendMessageEmbeds(any<MessageEmbed>(), *anyVararg()) }
    }

    @Test
    fun `status - shows no campaign message when none exists`() {
        every { event.subcommandName } returns "status"
        every { campaignService.getActiveCampaignForGuild(guildId) } returns null

        command.handle(DefaultCommandContext(event), requestingUserDto, 5)

        verify { event.hook.sendMessage(match<String> { it.contains("no active campaign") }) }
    }

    // end

    @Test
    fun `end - DM can end the campaign`() {
        every { event.subcommandName } returns "end"
        every { requestingUserDto.discordId } returns dmDiscordId
        every { campaignService.getActiveCampaignForGuild(guildId) } returns makeCampaign()
        every { campaignService.deactivateCampaignForGuild(guildId) } just Runs

        command.handle(DefaultCommandContext(event), requestingUserDto, 5)

        verify { campaignService.deactivateCampaignForGuild(guildId) }
        verify { event.hook.sendMessage(match<String> { it.contains("ended") }) }
    }

    @Test
    fun `end - non-DM is rejected`() {
        every { event.subcommandName } returns "end"
        every { requestingUserDto.discordId } returns playerDiscordId
        every { requestingUserDto.superUser } returns false
        every { campaignService.getActiveCampaignForGuild(guildId) } returns makeCampaign()

        command.handle(DefaultCommandContext(event), requestingUserDto, 5)

        verify(exactly = 0) { campaignService.deactivateCampaignForGuild(any()) }
        verify { event.hook.sendMessage(match<String> { it.contains("Dungeon Master") }) }
    }

    @Test
    fun `end - superUser can end another DM campaign`() {
        every { event.subcommandName } returns "end"
        every { requestingUserDto.discordId } returns playerDiscordId
        every { requestingUserDto.superUser } returns true
        every { campaignService.getActiveCampaignForGuild(guildId) } returns makeCampaign()
        every { campaignService.deactivateCampaignForGuild(guildId) } just Runs

        command.handle(DefaultCommandContext(event), requestingUserDto, 5)

        verify { campaignService.deactivateCampaignForGuild(guildId) }
    }
}
