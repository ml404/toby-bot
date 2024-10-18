package bot.toby.command.commands.moderation

import core.command.CommandContext
import database.service.UserService
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class SocialCreditCommand @Autowired constructor(private val userService: UserService) : ModerationCommand {
    private val LEADERBOARD = "leaderboard"
    private val USERS = "users"
    private val SOCIAL_CREDIT = "credit"

    override fun handle(ctx: CommandContext, requestingUserDto: database.dto.UserDto, deleteDelay: Int?) {
        val event = ctx.event
        event.deferReply(true).queue()
        val member = ctx.member
        val guild = event.guild ?: return
        if (!guild.isLoaded) guild.loadMembers()

        if (event.getOption(LEADERBOARD)?.asBoolean == true) {
            createAndPrintLeaderboard(event, deleteDelay)
            return
        }
        calculateAndUpdateSocialCredit(event, requestingUserDto, member, deleteDelay)
    }

    private fun calculateAndUpdateSocialCredit(
        event: SlashCommandInteractionEvent,
        requestingUserDto: database.dto.UserDto?,
        requestingMember: Member?,
        deleteDelay: Int?
    ) {
        val user = event.getOption(USERS)?.asUser
        if (user == null) {
            listSocialCreditScore(event, requestingUserDto, requestingMember?.effectiveName ?: "Unknown", deleteDelay)
            return
        }

        // Check to see if the database contained an entry for the user we have made a request against
        val targetUserDto = userService.getUserById(user.idLong, requestingUserDto?.guildId ?: return) ?: return

        if (requestingMember?.isOwner == true && requestingUserDto.guildId == targetUserDto.guildId) {
            event.getOption(SOCIAL_CREDIT)?.asLong?.takeIf { it != Long.MIN_VALUE }?.let { socialCreditScore ->                val updatedUser = updateUserSocialCredit(targetUserDto, socialCreditScore)
                event.hook.sendMessage("Updated user ${user.effectiveName}'s social credit by $socialCreditScore. New score is: ${updatedUser.socialCredit}")
                    .setEphemeral(true)
                    .queue(core.command.Command.Companion.invokeDeleteOnMessageResponse(deleteDelay!!))
            } ?: listSocialCreditScore(event, targetUserDto, user.effectiveName, deleteDelay)
        } else {
            event.hook
                .sendMessage("User '${requestingMember?.effectiveName ?: "Unknown"}' is not allowed to adjust the social credit of user '${user.effectiveName}'.")
                .setEphemeral(true)
                .queue(core.command.Command.Companion.invokeDeleteOnMessageResponse(deleteDelay!!))
        }
    }

    private fun createAndPrintLeaderboard(event: SlashCommandInteractionEvent, deleteDelay: Int?) {
        val guild = event.guild ?: return
        val socialCreditMap = userService.listGuildUsers(guild.idLong).associate { it?.discordId to it?.socialCredit }

        val leaderboard = socialCreditMap.entries
            .sortedByDescending { it.value }
            .mapIndexed { index, entry ->
                val member = entry.key?.let { guild.getMemberById(it) }
                "#${index + 1}: ${member?.effectiveName ?: "Unknown"} - score: ${entry.value}"
            }

        val message = buildString {
            append("**Social Credit Leaderboard**\n")
            append("**-----------------------------**\n")
            append(leaderboard.joinToString("\n"))
        }

        event.hook.sendMessage(message)
            .setEphemeral(true)
            .queue(core.command.Command.Companion.invokeDeleteOnMessageResponse(deleteDelay!!))
    }

    private fun listSocialCreditScore(
        event: SlashCommandInteractionEvent,
        userDto: database.dto.UserDto?,
        mentionedName: String,
        deleteDelay: Int?
    ) {
        val socialCredit = userDto?.socialCredit ?: 0L
        event.hook.sendMessage("${mentionedName}'s social credit is: $socialCredit")
            .setEphemeral(true)
            .queue(core.command.Command.Companion.invokeDeleteOnMessageResponse(deleteDelay!!))
    }

    private fun updateUserSocialCredit(
        targetUserDto: database.dto.UserDto,
        socialCreditScore: Long
    ): database.dto.UserDto {
        targetUserDto.socialCredit = targetUserDto.socialCredit?.plus(socialCreditScore)
        userService.updateUser(targetUserDto)
        return targetUserDto
    }

    override val name: String
        get() = "socialcredit"

    override val description: String
        get() = "Use this command to adjust the mentioned user's social credit."

    override val optionData: List<OptionData>
        get() = listOf(
            OptionData(OptionType.BOOLEAN, LEADERBOARD, "Show the leaderboard"),
            OptionData(
                OptionType.USER,
                USERS,
                "User(s) to adjust the social credit value. Without a value will display their social credit amount"
            ),
            OptionData(OptionType.NUMBER, SOCIAL_CREDIT, "Score to add or deduct from mentioned user's social credit")
        )
}
