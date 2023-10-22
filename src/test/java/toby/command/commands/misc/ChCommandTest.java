package toby.command.commands.misc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import toby.command.commands.CommandTest;

import static org.junit.jupiter.api.Assertions.*;

class ChCommandTest implements CommandTest {

    ChCommand command;

    @BeforeEach
    void setUp() {
        command = new ChCommand();
        setUpCommonMocks();
    }

    @AfterEach
    void tearDown() {
        tearDownCommonMocks();
    }
}