package toby.command;

import toby.jpa.dto.UserDto;

import java.util.Arrays;
import java.util.List;

public interface ICommand {
    void handle(CommandContext ctx, String prefix, UserDto requestingUserDto);

    String getName();

    String getHelp(String prefix);

    default List<String> getAliases() {
        return Arrays.asList();
    }
}