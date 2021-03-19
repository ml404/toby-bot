package toby;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static toby.BotMain.connection;

public class DatabaseHelper {

    public static Long badOpinionChannel = 756262044491055165L;
    public static Long tobyId = 320919876883447808L;


    public static String getConfigValue(String name) {
        try {
            String query = connection.nativeSQL(String.format("select value from public.config where name ='%s'", name.toUpperCase()));
            PreparedStatement preparedStatement = connection.prepareStatement(query);
            ResultSet resultSet = preparedStatement.executeQuery();
            return resultSet.next() ? resultSet.getString("value") : "";
        } catch (SQLException throwables) {
            throwables.printStackTrace();
        }
        return "";
    }

    public static String getBrotherName(String userId) {
        try {
            String query = connection.nativeSQL(String.format("select brother_name from public.brothers where discord_id ='%s'", userId));
            PreparedStatement preparedStatement = connection.prepareStatement(query);
            ResultSet resultSet = preparedStatement.executeQuery();
            return resultSet.next() ? resultSet.getString("brother_name") : "";
        } catch (SQLException throwables) {
            throwables.printStackTrace();
        }
        return "";
    }
}