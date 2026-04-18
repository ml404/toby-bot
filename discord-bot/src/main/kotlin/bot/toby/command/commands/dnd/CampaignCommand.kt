package bot.toby.command.commands.dnd

import bot.toby.BOT_WEB_URL
import bot.toby.helpers.UserDtoHelper
import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.CommandContext
import database.dto.CampaignDto
import database.dto.CampaignPlayerDto
import database.dto.CampaignPlayerId
import database.service.CampaignPlayerService
import database.service.CampaignService
import database.dto.UserDto
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import org.springframework.stereotype.Component
import java.awt.Color

@Component
class CampaignCommand(
    private val campaignService: CampaignService,
    private val campaignPlayerService: CampaignPlayerService,
    private val userDtoHelper: UserDtoHelper
) : DnDCommand {

    override val name = "campaign"
    override val description = "Manage a D&D campaign for this server"

    override val subCommands: List<SubcommandData> = listOf(
        SubcommandData("create", "Create a new campaign for this server (you become the DM)")
            .addOptions(
                OptionData(OptionType.STRING, "name", "Campaign name", true),
                OptionData(OptionType.CHANNEL, "channel", "Channel associated with the campaign", false)
            ),
        SubcommandData("join", "Join the active campaign as a player"),
        SubcommandData("leave", "Leave the active campaign"),
        SubcommandData("status", "Show the active campaign and its players"),
        SubcommandData("end", "End the active campaign (DM only)")
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply().queue()

        val guild = event.guild ?: run {
            event.hook.sendMessage("This command can only be used in a server.")
                .queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }

        val member = ctx.member ?: run {
            event.hook.sendMessage("Could not resolve your member info.")
                .queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }

        val callerDto = userDtoHelper.calculateUserDto(member.idLong, guild.idLong, member.isOwner)

        when (event.subcommandName) {
            "create" -> handleCreate(ctx, guild, callerDto, deleteDelay)
            "join"   -> handleJoin(ctx, guild, callerDto, deleteDelay)
            "leave"  -> handleLeave(ctx, guild, callerDto, deleteDelay)
            "status" -> handleStatus(ctx, guild, deleteDelay)
            "end"    -> handleEnd(ctx, guild, callerDto, deleteDelay)
            else -> event.hook.sendMessage("Unknown subcommand.")
                .queue(invokeDeleteOnMessageResponse(deleteDelay))
        }
    }

    private fun handleCreate(ctx: CommandContext, guild: Guild, callerDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        val existingCampaign = campaignService.getActiveCampaignForGuild(guild.idLong)
        if (existingCampaign != null) {
            event.hook.sendMessage(
                "There is already an active campaign: **${existingCampaign.name}**. " +
                "Use `/campaign end` to close it first."
            ).queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }

        val campaignName = event.getOption("name")?.asString ?: "Unnamed Campaign"
        val channelId = event.getOption("channel")?.asChannel?.idLong
            ?: event.channel.idLong

        val campaign = CampaignDto(
            guildId = guild.idLong,
            channelId = channelId,
            dmDiscordId = callerDto.discordId,
            name = campaignName
        )
        val saved = campaignService.createCampaign(campaign)

        val embed = EmbedBuilder()
            .setTitle("⚔️ Campaign Created: ${saved.name}")
            .setColor(Color(88, 101, 242))
            .addField("Dungeon Master", ctx.member?.effectiveName ?: "Unknown", true)
            .addField("Guild", guild.name, true)
            .addField("Players", "None yet — use `/campaign join` to join!", false)
            .addField("Web UI", "$BOT_WEB_URL/dnd/campaign/${guild.idLong}", false)
            .build()

        event.hook.sendMessageEmbeds(embed).queue()
    }

    private fun handleJoin(ctx: CommandContext, guild: Guild, callerDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        val campaign = campaignService.getActiveCampaignForGuild(guild.idLong) ?: run {
            event.hook.sendMessage("There is no active campaign in this server. Use `/campaign create` to start one.")
                .queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }

        if (callerDto.discordId == campaign.dmDiscordId) {
            event.hook.sendMessage("You are the DM of this campaign and cannot join as a player.")
                .queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }

        val playerId = CampaignPlayerId(campaign.id, callerDto.discordId)
        if (campaignPlayerService.getPlayer(playerId) != null) {
            event.hook.sendMessage("You are already in this campaign.")
                .queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }

        val player = CampaignPlayerDto(
            id = playerId,
            campaign = campaign,
            guildId = guild.idLong,
            characterId = callerDto.dndBeyondCharacterId
        )
        campaignPlayerService.addPlayer(player)

        val characterNote = if (callerDto.dndBeyondCharacterId != null)
            "Character linked (ID: ${callerDto.dndBeyondCharacterId})"
        else
            "No character linked yet — use `/linkcharacter` to link one"

        val embed = EmbedBuilder()
            .setTitle("🎲 Joined Campaign: ${campaign.name}")
            .setColor(Color(88, 101, 242))
            .addField("Player", ctx.member?.effectiveName ?: "Unknown", true)
            .addField("Character", characterNote, false)
            .addField("Web UI", "$BOT_WEB_URL/dnd/campaign/${guild.idLong}", false)
            .build()

        event.hook.sendMessageEmbeds(embed).queue()
    }

    private fun handleLeave(ctx: CommandContext, guild: Guild, callerDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        val campaign = campaignService.getActiveCampaignForGuild(guild.idLong) ?: run {
            event.hook.sendMessage("There is no active campaign in this server.")
                .queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }

        val playerId = CampaignPlayerId(campaign.id, callerDto.discordId)
        if (campaignPlayerService.getPlayer(playerId) == null) {
            event.hook.sendMessage("You are not in this campaign.")
                .queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }

        campaignPlayerService.removePlayer(playerId)
        event.hook.sendMessage("You have left the campaign **${campaign.name}**.")
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun handleStatus(ctx: CommandContext, guild: Guild, deleteDelay: Int) {
        val event = ctx.event
        val campaign = campaignService.getActiveCampaignForGuild(guild.idLong) ?: run {
            event.hook.sendMessage("There is no active campaign in this server. Use `/campaign create` to start one.")
                .queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }

        val players = campaignPlayerService.getPlayersForCampaign(campaign.id)
        val dmMember = guild.getMemberById(campaign.dmDiscordId)
        val dmName = dmMember?.effectiveName ?: "Unknown (ID: ${campaign.dmDiscordId})"

        val playerList = if (players.isEmpty()) {
            "No players yet"
        } else {
            players.joinToString("\n") { p ->
                val memberName = guild.getMemberById(p.id.playerDiscordId)?.effectiveName
                    ?: "Unknown (ID: ${p.id.playerDiscordId})"
                val status = if (!p.alive) " ☠️" else ""
                val charNote = p.characterId?.let { " — char ID: $it" } ?: ""
                "• $memberName$status$charNote"
            }
        }

        val embed = EmbedBuilder()
            .setTitle("⚔️ ${campaign.name}")
            .setColor(Color(88, 101, 242))
            .addField("Dungeon Master", dmName, true)
            .addField("Guild", guild.name, true)
            .addField("Players (${players.size})", playerList, false)
            .addField("Web UI", "$BOT_WEB_URL/dnd/campaign/${guild.idLong}", false)
            .build()

        event.hook.sendMessageEmbeds(embed).queue()
    }

    private fun handleEnd(ctx: CommandContext, guild: Guild, callerDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        val campaign = campaignService.getActiveCampaignForGuild(guild.idLong) ?: run {
            event.hook.sendMessage("There is no active campaign in this server.")
                .queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }

        if (callerDto.discordId != campaign.dmDiscordId && !callerDto.superUser) {
            event.hook.sendMessage("Only the Dungeon Master can end the campaign.")
                .queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }

        campaignService.deactivateCampaignForGuild(guild.idLong)
        event.hook.sendMessage("The campaign **${campaign.name}** has ended. Thanks for playing!")
            .queue()
    }
}
