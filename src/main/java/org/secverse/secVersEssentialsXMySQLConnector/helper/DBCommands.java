package org.secverse.secVersEssentialsXMySQLConnector.helper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public class DBCommands {
    private final Connection conn;
    public DBCommands(Connection conn) { this.conn = conn; }

    public void upsertUser(UUID uuid, String name, double balance, String lastLoc, String homes, String group, long timestamp) throws SQLException {
        String sql = """
            INSERT INTO essentials_users (uuid, name, balance, last_location, homes, group, synced, last_update)
            VALUES (?, ?, ?, ?, ?, ?, TRUE, ?)
            ON DUPLICATE KEY UPDATE\s
                name=VALUES(name),\s
                balance=VALUES(balance),\s
                last_location=VALUES(last_location),\s
                homes=VALUES(homes),\s
                group=VALUES(group),\s
                synced=TRUE,
                last_update=VALUES(last_update)""";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setDouble(3, balance);
            ps.setString(4, lastLoc);
            ps.setString(5, homes);
            ps.setString(6, group);
            ps.setLong(7, timestamp);
            ps.executeUpdate();
        }
    }


    public ResultSet getUser(UUID uuid) throws SQLException {
        String sql = "SELECT * FROM essentials_users WHERE uuid = ?";
        PreparedStatement ps = conn.prepareStatement(sql);
        ps.setString(1, uuid.toString());
        return ps.executeQuery();
    }


    void updateBalance(UUID uuid, double balance) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE essentials_users SET balance = ? WHERE uuid = ?")) {
            ps.setDouble(1, balance);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }


    void updateHomes(UUID uuid, String homesJson) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE essentials_users SET homes = ? WHERE uuid = ?")) {
            ps.setString(1, homesJson);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }
}