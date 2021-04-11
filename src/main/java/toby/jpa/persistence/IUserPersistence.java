package toby.jpa.persistence;

import toby.jpa.dto.UserDto;

import java.util.List;

public interface IUserPersistence {

    List<UserDto> listGuildUsers(Long guildId);
    UserDto createNewUser(UserDto userDto);
    UserDto getUserById(Long discordId, Long guildId);
    UserDto updateUser(UserDto userDto);
    void deleteUser(UserDto userDto);
    void deleteUserById(Long discordId, Long guildId);
}
