package jpa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import toby.Application;
import toby.jpa.dto.UserDto;
import toby.jpa.persistence.IUserPersistence;
import toby.jpa.service.IUserService;
import toby.jpa.service.impl.UserServiceImpl;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = Application.class)
public class UserServiceImplIntegrationTest {

    @Bean
    public IUserService userService() {
        return new UserServiceImpl();
    }

    @Autowired
    private IUserService userService;

    @Autowired
    private IUserPersistence userPersistence;


    @BeforeEach
    public void setUp() {

    }

    @AfterEach
    public void tearDown(){
        userService.deleteUserById(1L, 1L);
    }

    @Test
    public void whenValidDiscordIdAndGuild_thenUserShouldBeFound() {
        UserDto userDto = new UserDto();
        userDto.setDiscordId(1L);
        userDto.setGuildId(1L);
        userService.createNewUser(userDto);
        UserDto dbUser = userService.getUserById(userDto.getDiscordId(), userDto.getGuildId());

        assertEquals(dbUser.getDiscordId(),userDto.getDiscordId());
        assertEquals(dbUser.getGuildId(),userDto.getGuildId());
        assertTrue(dbUser.hasMusicPermission());
        assertTrue(dbUser.hasMemePermission());
        assertTrue(dbUser.hasDigPermission());
        assertFalse(dbUser.isSuperUser());
    }

    @Test
    public void testUpdate_thenNewUserValuesShouldBeReturned() {
        UserDto userDto1 = new UserDto();
        userDto1.setDiscordId(1L);
        userDto1.setGuildId(1L);
        userService.createNewUser(userDto1);
        UserDto dbUser1 = userService.getUserById(userDto1.getDiscordId(), userDto1.getGuildId());

        UserDto userDto2 = userDto1;
        userDto2.setDigPermission(false);
        userService.updateUser(userDto2);
        UserDto dbUser2 = userService.getUserById(userDto2.getDiscordId(), userDto2.getGuildId());

        int guildMemberSize = userService.listGuildUsers(1L).size();

        assertEquals(dbUser1.getDiscordId(),userDto1.getDiscordId());
        assertEquals(dbUser1.getGuildId(),userDto1.getGuildId());
        assertTrue(dbUser1.hasMusicPermission());
        assertTrue(dbUser1.hasMemePermission());
        assertTrue(dbUser1.hasDigPermission());
        assertFalse(dbUser1.isSuperUser());

        assertEquals(dbUser2.getDiscordId(),userDto2.getDiscordId());
        assertEquals(dbUser2.getGuildId(),userDto2.getGuildId());
        assertTrue(dbUser2.hasMusicPermission());
        assertTrue(dbUser2.hasMemePermission());
        assertFalse(dbUser2.hasDigPermission());
        assertFalse(dbUser2.isSuperUser());
        assertEquals(1, guildMemberSize);
    }
}
