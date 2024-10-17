package bot.toby.helpers

import database.dto.MusicDto
import database.dto.UserDto
import database.service.IUserService
import common.logging.DiscordLogger
import net.dv8tion.jda.api.entities.Member
import org.springframework.stereotype.Service

@Service
class UserDtoHelper(private val userService: IUserService) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)
    fun calculateUserDto(
        discordId: Long,
        guildId: Long,
        isSuperUser: Boolean = false
    ): UserDto {
        logger.info("Processing lookup for user: $discordId, guild: $guildId")
        return userService.getUserById(discordId, guildId) ?: UserDto(discordId, guildId).apply {
            this.superUser = isSuperUser
            this.musicDtos = emptyList<MusicDto>().toMutableList()
            userService.createNewUser(this)
        }
    }

    fun userAdjustmentValidation(requester: UserDto, target: UserDto): Boolean {
        return requester.superUser && !target.superUser
    }

    fun updateUser(userDto: UserDto) {
        userService.updateUser(userDto)
    }

    companion object {
        fun Member.getRequestingUserDto(userDtoHelper: UserDtoHelper): UserDto {
            val discordId = this.idLong
            val guildId = this.guild.idLong
            return userDtoHelper.calculateUserDto(discordId, guildId, this.isOwner)
        }
    }
}
