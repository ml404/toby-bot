package jpa;

import org.apache.commons.collections4.IterableUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import toby.Application;
import toby.jpa.dto.ExcuseDto;
import toby.jpa.service.IExcuseService;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = Application.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("test")
public class ExcuseServiceImplIntegrationTest {

    @Autowired
    private IExcuseService excuseService;


    @BeforeEach
    public void setUp() {
        excuseService.clearCache();
    }

    @AfterEach
    public void tearDown() {
    }


    @Test
    public void testDataSQL() {
        assertEquals(2, IterableUtils.toList(excuseService.listAllGuildExcuses(1L)).size());
    }
    @Test
    public void whenValidIdAndGuild_thenExcuseShouldBeFound() {
        ExcuseDto excuseDto = new ExcuseDto();
        excuseDto.setGuildId(1L);
        excuseDto.setAuthor("Author");
        excuseDto.setExcuse("Excuse");
        excuseDto.setApproved(false);
        excuseService.createNewExcuse(excuseDto);
        ExcuseDto dbExcuse = excuseService.getExcuseById(excuseDto.getId());

        assertEquals(dbExcuse.getId(), excuseDto.getId());
        assertEquals(dbExcuse.getGuildId(), excuseDto.getGuildId());
        assertEquals(dbExcuse.getAuthor(), excuseDto.getAuthor());
        assertEquals(dbExcuse.getExcuse(), excuseDto.getExcuse());
        assertFalse(dbExcuse.isApproved());
        excuseService.deleteExcuseById(excuseDto.getId());
    }

    @Test
    public void testUpdate_thenNewExcuseValuesShouldBeReturned() {
        ExcuseDto excuseDto1 = new ExcuseDto();
        excuseDto1.setGuildId(1L);
        excuseDto1.setAuthor("Author");
        excuseDto1.setExcuse("Excuse");
        excuseDto1.setApproved(false);

        excuseDto1 = excuseService.createNewExcuse(excuseDto1);
        ExcuseDto dbExcuse1 = excuseService.getExcuseById(excuseDto1.getId());
        int pendingExcusesSize = excuseService.listPendingGuildExcuses(excuseDto1.getGuildId()).size();
        int approvedExcusesSize = excuseService.listApprovedGuildExcuses(excuseDto1.getGuildId()).size();


        assertEquals(2, pendingExcusesSize);
        assertEquals(1, approvedExcusesSize);
        assertEquals(dbExcuse1.getGuildId(), excuseDto1.getGuildId());
        assertEquals(dbExcuse1.getExcuse(), excuseDto1.getExcuse());
        assertEquals(dbExcuse1.getAuthor(), excuseDto1.getAuthor());
        assertFalse(dbExcuse1.isApproved());


        ExcuseDto excuseDto2 = new ExcuseDto();
        excuseDto2.setId(excuseDto1.getId());
        excuseDto2.setGuildId(1L);
        excuseDto2.setAuthor("Author");
        excuseDto2.setExcuse("Excuse");
        excuseDto2.setApproved(true);

        excuseDto2 = excuseService.updateExcuse(excuseDto2);
        excuseService.clearCache();
        ExcuseDto dbExcuse2 = excuseService.getExcuseById(excuseDto2.getId());

        pendingExcusesSize = excuseService.listPendingGuildExcuses(excuseDto2.getGuildId()).size();
        approvedExcusesSize = excuseService.listApprovedGuildExcuses(excuseDto2.getGuildId()).size();

        assertEquals(1, pendingExcusesSize);
        assertEquals(2, approvedExcusesSize);
        assertEquals(dbExcuse2.getGuildId(), excuseDto2.getGuildId());
        assertEquals(dbExcuse2.getExcuse(), excuseDto2.getExcuse());
        assertEquals(dbExcuse2.getAuthor(), excuseDto2.getAuthor());
        assertTrue(dbExcuse2.isApproved());
        excuseService.deleteExcuseById(excuseDto1.getId());
    }
}
