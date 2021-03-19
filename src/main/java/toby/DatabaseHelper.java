package toby;

import java.sql.SQLException;

import static toby.BotMain.connection;

public class DatabaseHelper {

    public static Long badOpinionChannel = 756262044491055165L;
    public static Long tobyId = 320919876883447808L;


    public static String getConfigValue(String name) {
        try {
            String query = connection.nativeSQL(String.format("select value from public.config where name ='%s'", name.toUpperCase()));
            return connection.createStatement().executeQuery(query).toString();
        } catch (SQLException throwables) {
            throwables.printStackTrace();
        }
        return "";
    }

    public static String getBrotherName(String userId) {
        try {
            String query = connection.nativeSQL(String.format("select brother_name from public.brothers where user_id ='%s'", userId));
            return connection.createStatement().executeQuery(query).toString();
        } catch (SQLException throwables) {
            throwables.printStackTrace();
        }
        return "";
    }
}