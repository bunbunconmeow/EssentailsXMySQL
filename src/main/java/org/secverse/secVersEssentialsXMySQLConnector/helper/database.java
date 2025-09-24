package org.secverse.secVersEssentialsXMySQLConnector.helper;

import org.bukkit.configuration.file.FileConfiguration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;


public class database {
    private final String host, database, user, password;
    private final int port;
    private Connection connection;

    public database(FileConfiguration config) {
        this.host = config.getString("mysql.host", "localhost");
        this.port = config.getInt("mysql.port", 3306);
        this.database = config.getString("mysql.database", "essentials");
        this.user = config.getString("mysql.user", "root");
        this.password = config.getString("mysql.password", "");
    }


    public void connect() throws SQLException {
        if (connection != null && !connection.isClosed()) return;

        String prefix = "jdbc:mysql";
        String url = String.format("%s://%s:%d/%s?useSSL=false&autoReconnect=true", prefix, host, port, database);

        connection = DriverManager.getConnection(url, user, password);
    }

    public void setupTable() throws SQLException {
        if(!isConnected()) {
            connect();
        }
        String ddl = """
            CREATE TABLE IF NOT EXISTS essentials_users (
              uuid          CHAR(36) PRIMARY KEY,
              name          VARCHAR(32),
              balance       DOUBLE,
              groupname     VARCHAR(64),
              last_location TEXT,
              homes         TEXT,
              synced        BOOLEAN DEFAULT TRUE,
              last_update   BIGINT
            )""";
        try (Statement st = connection.createStatement()) {
            st.executeUpdate(ddl);
        }
    }



    public Connection getConnection() {
        return connection;
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {}
    }


    public boolean isConnected() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }
}
