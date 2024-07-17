package toby.helpers

import toby.jpa.dto.MusicDto
import toby.jpa.dto.UserDto
import toby.jpa.service.IUserService

object UserDtoHelper {
    fun calculateUserDto(
        guildId: Long,
        discordId: Long,
        isSuperUser: Boolean,
        userService: IUserService,
        introVolume: Int = 20
    ): UserDto {
        return userService.getUserById(discordId, guildId) ?: UserDto().apply {
            this.discordId = discordId
            this.guildId = guildId
            this.superUser = isSuperUser
            this.musicDto = MusicDto(discordId, guildId, null, introVolume, null)
            userService.createNewUser(this)
        }
    }


        fun userAdjustmentValidation(requester: UserDto, target: UserDto): Boolean {
        return requester.superUser && !target.superUser
    }
}
