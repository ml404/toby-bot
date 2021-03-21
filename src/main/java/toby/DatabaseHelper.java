package toby;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.*;

import static toby.BotMain.connection;

public class DatabaseHelper {

    public static Long badOpinionChannel = 756262044491055165L;
    public static Long tobyId = 320919876883447808L;

    public static Connection getConnection() throws URISyntaxException, SQLException {
        URI dbUri = new URI(System.getenv("DATABASE_URL"));

        String username = dbUri.getUserInfo().split(":")[0];
        String password = dbUri.getUserInfo().split(":")[1];
        String dbUrl = "jdbc:postgresql://" + dbUri.getHost() + ':' + dbUri.getPort() + dbUri.getPath() + "?sslmode=require";

        return DriverManager.getConnection(dbUrl, username, password);
    }

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