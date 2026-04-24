package bot.toby.command.commands.misc

import bot.toby.activity.ActivityTrackingService
import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.CommandContext
import database.dto.UserDto
import database.service.ActivityMonthlyRollupService
import database.service.UserService
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneOffset

@Component
class ActivityCommand @Autowired constructor(
    private val rollupService: ActivityMonthlyRollupService,
    private val userService: UserService,
    private val activityTrackingService: ActivityTrackingService
) : MiscCommand {

    override val name: String = "activity"
    override val description: String = "Your top games, server activity stats, and per-user tracking preference."

    override val subCommands: List<SubcommandData> = listOf(
        SubcommandData("me", "Show your own top games this month and across the last 12 months."),
        SubcommandData("server", "Show the server's top games this month."),
        SubcommandData("tracking-on", "Opt in to game-activity tracking in this server."),
        SubcommandData("tracking-off", "Opt out of game-activity tracking in this server.")
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply(true).queue()

        val guild = event.guild ?: run {
            reply(event, "This command can only be used in a server.", deleteDelay)
            return
        }

        when (event.subcommandName) {
            "me" -> showMe(event, requestingUserDto, deleteDelay)
            "server" -> showServer(event, guild.idLong, deleteDelay)
            "tracking-on" -> setOptOut(event, requestingUserDto, false, deleteDelay)
            "tracking-off" -> setOptOut(event, requestingUserDto, true, deleteDelay)
            else -> reply(event, "Unknown subcommand.", deleteDelay)
        }
    }

    private fun showMe(event: SlashCommandInteractionEvent, userDto: UserDto, deleteDelay: Int) {
        val guildId = userDto.guildId
        if (!activityTrackingService.isGuildTrackingEnabled(guildId)) {
            reply(event, "Activity tracking is disabled in this server.", deleteDelay)
            return
        }
        if (userDto.activityTrackingOptOut) {
            reply(
                event,
                "You have opted out of activity tracking here. Use `/activity tracking-on` to opt back in.",
                deleteDelay
            )
            return
        }

        val thisMonth = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)
        val monthRows = rollupService.forUserMonth(guildId, userDto.discordId, thisMonth).take(5)
        val allRows = rollupService.forUser(guildId, userDto.discordId)

        val lifetimeTotals = allRows
            .groupBy { it.activityName }
            .mapValues { (_, rows) -> rows.sumOf { it.seconds } }
            .entries
            .sortedByDescending { it.value }
            .take(5)

        val embed = EmbedBuilder()
            .setTitle("Your game activity")
            .setDescription(
                buildString {
                    append("**This month**\n")
                    if (monthRows.isEmpty()) {
                        append("_Nothing recorded yet._\n")
                    } else {
                        monthRows.forEach { append("• ${it.activityName} — ${formatDuration(it.seconds)}\n") }
                    }
                    append("\n**Last 12 months**\n")
                    if (lifetimeTotals.isEmpty()) {
                        append("_Nothing recorded yet._")
                    } else {
                        lifetimeTotals.forEach { append("• ${it.key} — ${formatDuration(it.value)}\n") }
                    }
                }
            )
            .build()
        event.hook.sendMessageEmbeds(embed)
            .setEphemeral(true)
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun showServer(event: SlashCommandInteractionEvent, guildId: Long, deleteDelay: Int) {
        if (!activityTrackingService.isGuildTrackingEnabled(guildId)) {
            reply(event, "Activity tracking is disabled in this server.", deleteDelay)
            return
        }
        val thisMonth = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)
        val optedOut = userService.listGuildUsers(guildId)
            .filterNotNull()
            .filter { it.activityTrackingOptOut }
            .map { it.discordId }
            .toSet()

        val rows = rollupService.forGuildMonth(guildId, thisMonth)
            .filter { it.discordId !in optedOut }
            .groupBy { it.activityName }
            .mapValues { (_, rows) -> rows.sumOf { it.seconds } }
            .entries
            .sortedByDescending { it.value }
            .take(10)

        val embed = EmbedBuilder()
            .setTitle("Top games this month")
            .setDescription(
                if (rows.isEmpty()) "_Nothing recorded yet for this month._"
                else rows.joinToString("\n") { "**${it.key}** — ${formatDuration(it.value)}" }
            )
            .setFooter(
                if (optedOut.isEmpty()) "Counts every tracked user in this server."
                else "${optedOut.size} user(s) have opted out and are not counted."
            )
            .build()
        event.hook.sendMessageEmbeds(embed)
            .setEphemeral(true)
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun setOptOut(event: SlashCommandInteractionEvent, userDto: UserDto, optOut: Boolean, deleteDelay: Int) {
        userDto.activityTrackingOptOut = optOut
        userService.updateUser(userDto)
        val message = if (optOut)
            "Opted out of activity tracking for this server. Existing rollups are kept but new activity will not be recorded. You can re-enable with `/activity tracking-on`."
        else
            "Opted in to activity tracking for this server. Your game activity will be counted from now."
        reply(event, message, deleteDelay)
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "0m"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    private fun reply(event: SlashCommandInteractionEvent, message: String, deleteDelay: Int) {
        event.hook.sendMessage(message)
            .setEphemeral(true)
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }
}
