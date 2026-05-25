package bot.toby.command.commands.misc

import bot.toby.activity.ActivityTrackingService
import bot.toby.helpers.MenuHelper
import core.command.Command.Companion.deleteAfter
import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.Command.Companion.replyEphemeralAndDelete
import core.command.Command.Companion.replyEphemeralEmbedAndDelete
import core.command.CommandContext
import database.dto.user.UserDto
import database.service.activity.ActivityMonthlyRollupService
import database.service.user.UserService
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.selections.SelectOption
import net.dv8tion.jda.api.components.selections.StringSelectMenu
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
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

    companion object {
        const val OPT_MONTH = "month"
        const val MONTH_DESC = "YYYY-MM, last 12 months only (defaults to current month)"
    }

    override val subCommands: List<SubcommandData> = listOf(
        SubcommandData("me", "Show your own top games this month and across the last 12 months.")
            .addOptions(OptionData(OptionType.STRING, OPT_MONTH, MONTH_DESC, false)),
        SubcommandData("server", "Show the server's top games for a month.")
            .addOptions(OptionData(OptionType.STRING, OPT_MONTH, MONTH_DESC, false)),
        SubcommandData("tracking-on", "Opt in to game-activity tracking in this server."),
        SubcommandData("tracking-off", "Opt out of game-activity tracking in this server.")
    )

    override val ephemeral: Boolean = true

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event

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
        val requested = parseMonthOption(event)
        val selectedMonth = clampMonth(requested, thisMonth) ?: thisMonth
        val fellBack = requested != null && selectedMonth != requested

        val monthRows = rollupService.forUserMonth(guildId, userDto.discordId, selectedMonth).take(5)
        val allRows = rollupService.forUser(guildId, userDto.discordId)

        val lifetimeTotals = allRows
            .groupBy { it.activityName }
            .mapValues { (_, rows) -> rows.sumOf { it.seconds } }
            .entries
            .sortedByDescending { it.value }
            .take(5)

        val monthLabel = formatMonth(selectedMonth)
        val embed = EmbedBuilder()
            .setTitle("Your game activity")
            .setDescription(
                buildString {
                    append("**$monthLabel**\n")
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
            .apply {
                if (fellBack) setFooter("Requested month is outside the last 12 months — showing current month instead.")
            }
            .build()
        event.hook.replyEphemeralEmbedAndDelete(embed, deleteDelay)
    }

    private fun showServer(event: SlashCommandInteractionEvent, guildId: Long, deleteDelay: Int) {
        if (!activityTrackingService.isGuildTrackingEnabled(guildId)) {
            reply(event, "Activity tracking is disabled in this server.", deleteDelay)
            return
        }
        val thisMonth = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)
        val requested = parseMonthOption(event)
        val selectedMonth = clampMonth(requested, thisMonth) ?: thisMonth
        val fellBack = requested != null && selectedMonth != requested

        val optedOut = userService.listGuildUsers(guildId)
            .filterNotNull()
            .filter { it.activityTrackingOptOut }
            .map { it.discordId }
            .toSet()

        val rows = rollupService.forGuildMonth(guildId, selectedMonth)
            .filter { it.discordId !in optedOut }
            .groupBy { it.activityName }
            .mapValues { (_, rows) -> rows.sumOf { it.seconds } }
            .entries
            .sortedByDescending { it.value }
            .take(10)

        val monthLabel = formatMonth(selectedMonth)
        val footerLines = mutableListOf<String>()
        if (fellBack) footerLines += "Requested month is outside the last 12 months — showing current month instead."
        footerLines += if (optedOut.isEmpty()) "Counts every tracked user in this server."
                       else "${optedOut.size} user(s) have opted out and are not counted."

        val embed = EmbedBuilder()
            .setTitle("Top games — $monthLabel")
            .setDescription(
                if (rows.isEmpty()) "_Nothing recorded yet for that month._"
                else rows.joinToString("\n") { "**${it.key}** — ${formatDuration(it.value)}" }
            )
            .setFooter(footerLines.joinToString(" "))
            .build()

        val send = event.hook.sendMessageEmbeds(embed).setEphemeral(true)
        if (rows.isEmpty()) {
            send.queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }

        // Component id encodes guild + month (epoch day); activity name rides
        // on each option value. No registry — fully self-contained routing.
        val menu = StringSelectMenu.create("${MenuHelper.ACTIVITY_CONTRIB}:$guildId:${selectedMonth.toEpochDay()}")
            .setPlaceholder("See who contributed…")
            .addOptions(
                rows.map { entry ->
                    val safe = entry.key.take(100)
                    SelectOption.of(safe, safe)
                }
            )
            .build()

        send.addComponents(ActionRow.of(menu)).queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun parseMonthOption(event: SlashCommandInteractionEvent): LocalDate? {
        val raw = event.getOption(OPT_MONTH)?.asString?.takeIf { it.isNotBlank() } ?: return null
        val parts = raw.split("-")
        if (parts.size != 2) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        if (month !in 1..12) return null
        return runCatching { LocalDate.of(year, month, 1) }.getOrNull()
    }

    private fun clampMonth(requested: LocalDate?, thisMonth: LocalDate): LocalDate? {
        if (requested == null) return null
        val first = requested.withDayOfMonth(1)
        val oldest = thisMonth.minusMonths(11)
        if (first.isBefore(oldest) || first.isAfter(thisMonth)) return null
        return first
    }

    private fun formatMonth(d: LocalDate): String {
        val name = d.month.name.lowercase().replaceFirstChar { it.titlecase() }
        return "$name ${d.year}"
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
        event.hook.replyEphemeralAndDelete(message, deleteDelay)
    }
}
