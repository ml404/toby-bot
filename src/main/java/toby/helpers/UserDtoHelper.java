package toby.helpers;

import toby.jpa.dto.MusicDto;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IUserService;

import java.util.Optional;

public class UserDtoHelper {

    public static UserDto calculateUserDto(long guildId, long discordId, boolean isSuperUser, IUserService userService) {

        Optional<UserDto> dbUserDto = userService.listGuildUsers(guildId).stream().filter(userDto -> userDto.getGuildId().equals(guildId) && userDto.getDiscordId().equals(discordId)).findFirst();
        if (dbUserDto.isEmpty()) {
            UserDto userDto = new UserDto();
            userDto.setDiscordId(discordId);
            userDto.setGuildId(guildId);
            userDto.setSuperUser(isSuperUser);
            MusicDto musicDto = new MusicDto(userDto.getDiscordId(), userDto.getGuildId(), null, null);
            userDto.setMusicDto(musicDto);
            return userService.createNewUser(userDto);
        }
        return userService.getUserById(discordId, guildId);
    }

}
