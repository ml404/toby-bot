package toby.helpers

import mu.KotlinLogging
import org.springframework.stereotype.Service
import toby.jpa.dto.MusicDto
import toby.jpa.dto.UserDto
import toby.jpa.service.IUserService

@Service
class UserDtoHelper(private val userService: IUserService) {
    private val logger = KotlinLogging.logger {}

    fun calculateUserDto(
        guildId: Long,
        discordId: Long,
        isSuperUser: Boolean = false
    ): UserDto {
        logger.info("Processing lookup for user: $discordId, guild: $guildId")
        return userService.getUserById(discordId, guildId) ?: UserDto().apply {
            this.discordId = discordId
            this.guildId = guildId
            this.superUser = isSuperUser
            this.musicDtos = emptyList<MusicDto>().toMutableList()
            userService.createNewUser(this)
        }
    }

    fun userAdjustmentValidation(requester: UserDto, target: UserDto): Boolean {
        return requester.superUser && !target.superUser
    }
}
