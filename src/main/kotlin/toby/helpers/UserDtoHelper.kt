package toby.helpers

import org.springframework.stereotype.Service
import toby.jpa.dto.MusicDto
import toby.jpa.dto.UserDto
import toby.jpa.service.IUserService

@Service
class UserDtoHelper(private val userService: IUserService) {
    fun calculateUserDto(
        guildId: Long,
        discordId: Long,
        isSuperUser: Boolean = false
    ): UserDto {
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
