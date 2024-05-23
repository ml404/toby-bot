package toby.command.commands.moderation

import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import toby.command.CommandContext
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.jpa.dto.UserDto
import toby.jpa.service.IUserService
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

class SocialCreditCommand(private val userService: IUserService) : IModerationCommand {
    private val LEADERBOARD = "leaderboard"
    private val USERS = "users"
    private val SOCIAL_CREDIT = "credit"
    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx.event
        event.deferReply().queue()
        val member = ctx.member
        if (!event.guild!!.isLoaded) event.guild!!.loadMembers()
        if (Optional.ofNullable(event.getOption(LEADERBOARD)).map { obj: OptionMapping -> obj.getAsBoolean() }.orElse(false)) {
            createAndPrintLeaderboard(event, deleteDelay)
            return
        }
        calculateAndUpdateSocialCredit(event, requestingUserDto, member, deleteDelay)
    }

    private fun calculateAndUpdateSocialCredit(
        event: SlashCommandInteractionEvent,
        requestingUserDto: UserDto?,
        requestingMember: Member?,
        deleteDelay: Int?
    ) {
        val optionalUser = Optional.ofNullable(event.getOption(USERS)).map { obj: OptionMapping -> obj.getAsUser() }
        if (optionalUser.isEmpty) {
            listSocialCreditScore(event, requestingUserDto, requestingMember!!.effectiveName, deleteDelay)
            return
        }
        val user = optionalUser.get()
        //Check to see if the database contained an entry for the user we have made a request against
        val targetUserDto = userService.getUserById(user.idLong, requestingUserDto!!.guildId)
        if (targetUserDto != null) {
            val isSameGuild = requestingUserDto.guildId == targetUserDto.guildId
            if (requestingMember!!.isOwner && isSameGuild) {
                val scOptional = Optional.ofNullable(event.getOption(SOCIAL_CREDIT)).map { obj: OptionMapping -> obj.asLong }
                if (scOptional.isPresent) {
                    val updatedUser = updateUserSocialCredit(targetUserDto, scOptional.get())
                    event.hook.sendMessageFormat("Updated user %s's social credit by %d. New score is: %d", user.getEffectiveName(), scOptional.get(), updatedUser.socialCredit).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
                } else {
                    listSocialCreditScore(event, targetUserDto, user.getEffectiveName(), deleteDelay)
                }
            } else event.hook.sendMessageFormat("User '%s' is not allowed to adjust the social credit of user '%s'.", requestingMember.effectiveName, user.getEffectiveName()).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
        }
    }

    private fun createAndPrintLeaderboard(event: SlashCommandInteractionEvent, deleteDelay: Int?) {
        val discordSocialCreditMap: MutableMap<Long, Long> = HashMap()
        val guild = event.guild!!
        userService.listGuildUsers(guild.idLong).forEach { user ->
            val socialCredit = user?.socialCredit
            user?.discordId?.let { discordId ->
                socialCredit?.let { credit ->
                    discordSocialCreditMap[discordId] = credit
                }
            }
        }
        val stringBuilder = StringBuilder()
        stringBuilder.append("**Social Credit Leaderboard**\n")
        stringBuilder.append("**-----------------------------**\n")

        val sortedMap = discordSocialCreditMap
            .entries
            .sortedByDescending { it.value }

        val position = AtomicInteger()
        val members = guild.members
        sortedMap.forEach { (discordId, score) ->
            position.incrementAndGet()
            val member = members.first { it.idLong == discordId }
            stringBuilder.append("#${position.get()}: ${member.effectiveName} - score: $score\n")
        }

        event.hook.sendMessageFormat(stringBuilder.toString()).setEphemeral(true).queue {
            invokeDeleteOnMessageResponse(deleteDelay!!)
        }
    }

    private fun listSocialCreditScore(event: SlashCommandInteractionEvent, userDto: UserDto?, mentionedName: String, deleteDelay: Int?) {
        val socialCredit = userDto?.socialCredit
        event.hook.sendMessageFormat("%s's social credit is: %d", mentionedName, socialCredit).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
    }

    private fun updateUserSocialCredit(targetUserDto: UserDto, socialCreditScore: Long): UserDto {
        val socialCredit = targetUserDto.socialCredit
        targetUserDto.socialCredit = socialCredit.plus(socialCreditScore)
        userService.updateUser(targetUserDto)
        return targetUserDto
    }

    override val name: String
        get() = "socialcredit"
    override val description: String
        get() = "Use this command to adjust the mentioned user's social credit."
    override val optionData: List<OptionData>
        get() {
            val leaderboard = OptionData(OptionType.BOOLEAN, LEADERBOARD, "Show the leaderboard")
            val users = OptionData(OptionType.USER, USERS, "User(s) to adjust the social credit value. Without a value will display their social credit amount")
            val creditAmount = OptionData(OptionType.NUMBER, SOCIAL_CREDIT, "Score to add or deduct from mentioned user's social credit")
            return listOf(users, creditAmount, leaderboard)
        }
}
