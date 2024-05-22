package toby.helpers

import toby.jpa.dto.MusicDto
import toby.jpa.dto.UserDto
import toby.jpa.service.IUserService

object UserDtoHelper {
    @JvmStatic
    fun calculateUserDto(
        guildId: Long,
        discordId: Long,
        isSuperUser: Boolean,
        userService: IUserService,
        introVolume: Int
    ): UserDto? {
        val dbUserDto = userService.listGuildUsers(guildId).find { it?.guildId == guildId && it.discordId == discordId }

        return if (dbUserDto == null) {
            val userDto = UserDto().apply {
                this.discordId = discordId
                this.guildId = guildId
                this.superUser = isSuperUser
                this.musicDto = MusicDto(discordId, guildId, null, introVolume, null)
            }
            userService.createNewUser(userDto)
        } else {
            userService.getUserById(discordId, guildId)
        }
    }


        fun userAdjustmentValidation(requester: UserDto, target: UserDto): Boolean {
        return requester.superUser && !target.superUser
    }
}
