package toby.helpers

import org.springframework.stereotype.Service
import toby.jpa.dto.MusicDto
import toby.jpa.dto.UserDto
import toby.jpa.service.IUserService
import toby.logging.DiscordLogger

@Service
class UserDtoHelper(private val userService: IUserService) {
    private lateinit var logger: DiscordLogger
    fun calculateUserDto(
        guildId: Long,
        discordId: Long,
        isSuperUser: Boolean = false
    ): UserDto {
        logger = DiscordLogger(guildId)
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
}
