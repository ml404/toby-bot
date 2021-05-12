package toby.jpa.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import toby.jpa.dto.UserDto;
import toby.jpa.persistence.IUserPersistence;
import toby.jpa.service.IUserService;

import java.util.List;

@Service
public class UserServiceImpl implements IUserService {

    @Autowired
    IUserPersistence userService;

    @Override
    @CacheEvict(value = "users", allEntries = true)
    public List<UserDto> listGuildUsers(Long guildId) {
        return userService.listGuildUsers(guildId);
    }

    @Override
    @CachePut(value = "users", key = "#userDto.discordId+#userDto.guildId")
    public UserDto createNewUser(UserDto userDto) {
        return userService.createNewUser(userDto);
    }

    @Override
    @Cacheable(value = "users", key = "#discordId+#guildId")
    public UserDto getUserById(Long discordId, Long guildId) {
        return userService.getUserById(discordId, guildId);
    }

    @Override
    @CacheEvict(value = "users", key = "#userDto.discordId+#userDto.guildId")
    public UserDto updateUser(UserDto userDto) {
        return userService.updateUser(userDto);
    }

    @Override
    @CacheEvict(value = "users", key = "#userDto.discordId+#user.guildId")
    public void deleteUser(UserDto userDto) {
        userService.deleteUser(userDto);
    }

    @Override
    @CacheEvict(value = "users", key = "#discordId+#guildId")
    public void deleteUserById(Long discordId, Long guildId) {
        userService.deleteUserById(discordId, guildId);
    }


}
