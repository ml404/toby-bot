package bot.toby.helpers

import bot.database.dto.MusicDto
import bot.database.service.IUserService
import bot.logging.DiscordLogger
import net.dv8tion.jda.api.entities.Member
import org.springframework.stereotype.Service

@Service
class UserDtoHelper(private val userService: IUserService) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)
    fun calculateUserDto(
        discordId: Long,
        guildId: Long,
        isSuperUser: Boolean = false
    ): bot.database.dto.UserDto {
        logger.info("Processing lookup for user: $discordId, guild: $guildId")
        return userService.getUserById(discordId, guildId) ?: bot.database.dto.UserDto(discordId, guildId).apply {
            this.superUser = isSuperUser
            this.musicDtos = emptyList<MusicDto>().toMutableList()
            userService.createNewUser(this)
        }
    }

    fun userAdjustmentValidation(requester: bot.database.dto.UserDto, target: bot.database.dto.UserDto): Boolean {
        return requester.superUser && !target.superUser
    }

    companion object {
        fun Member.getRequestingUserDto(userDtoHelper: UserDtoHelper): bot.database.dto.UserDto {
            val discordId = this.idLong
            val guildId = this.guild.idLong
            return userDtoHelper.calculateUserDto(discordId, guildId, this.isOwner)
        }
    }
}
