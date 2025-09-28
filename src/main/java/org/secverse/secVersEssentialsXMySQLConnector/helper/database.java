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
    private final boolean enableSSL, autoCommit;

    public database(FileConfiguration config) {
        this.host = config.getString("mysql.host", "localhost");
        this.port = config.getInt("mysql.port", 3306);
        this.database = config.getString("mysql.database", "essentials");
        this.user = config.getString("mysql.user", "root");
        this.password = config.getString("mysql.password", "");
        this.enableSSL = config.getBoolean("mysql.enableSSL", false);
        this.autoCommit = config.getBoolean("mysql.autoCommit", false);
    }


    public void connect() throws SQLException {
        if (connection != null && !connection.isClosed()) return;

        String prefix = "jdbc:mysql";
        String url = String.format("%s://%s:%d/%s?useSSL=%b&autoReconnect=true&serverTimezone=UTC",
                prefix, host, port, database, enableSSL);

        connection = DriverManager.getConnection(url, user, password);
        connection.setAutoCommit(autoCommit);
    }

    /**
     * Creates the required table if it does not exist.
     */
    public void setupTable() throws SQLException {
        ensureConnected();

        // Global user table: one row per player across the whole network
        String ddlUsers = """
            CREATE TABLE IF NOT EXISTS essentials_users (
              uuid         CHAR(36)  NOT NULL PRIMARY KEY,
              name         VARCHAR(32) NOT NULL,
              balance      DOUBLE NOT NULL DEFAULT 0,
              last_update  BIGINT NOT NULL,
              INDEX idx_users_last_update (last_update)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

        // Per-server profile table group, homes, last_location for each
        String ddlProfiles = """
            CREATE TABLE IF NOT EXISTS essentials_user_profiles (
              uuid          CHAR(36)    NOT NULL,
              server_name   VARCHAR(64) NOT NULL,
              groupname     VARCHAR(64) NULL,
              last_location TEXT        NULL,
              homes         MEDIUMTEXT  NULL,
              last_update   BIGINT      NOT NULL,
              PRIMARY KEY (uuid, server_name),
              CONSTRAINT fk_profiles_users
                FOREIGN KEY (uuid) REFERENCES essentials_users (uuid)
                ON DELETE CASCADE,
              INDEX idx_profiles_last_update (last_update),
              INDEX idx_profiles_uuid (uuid),
              INDEX idx_profiles_server (server_name)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

        // Per-server player state: inventory, stats, xp, health, hunger
        String ddlState = """
        CREATE TABLE IF NOT EXISTS essentials_user_state (
          uuid           CHAR(36)    NOT NULL,
          server_name    VARCHAR(64) NOT NULL,

          -- Inventory blobs (driver-agnostic; store Base64 or NBT-binary)
          inv_main       MEDIUMBLOB  NULL,  -- full main inventory (36 slots)
          inv_offhand    BLOB        NULL,  -- offhand item
          inv_armor      BLOB        NULL,  -- 4 armor slots
          ender_chest    MEDIUMBLOB  NULL,  -- ender chest contents

          -- Core player stats
          xp_level       INT         NOT NULL DEFAULT 0,
          xp_total       INT         NOT NULL DEFAULT 0,
          xp_progress    FLOAT       NOT NULL DEFAULT 0,  -- 0..1 progress within current level
          health         DOUBLE      NOT NULL DEFAULT 20, -- current health
          max_health     DOUBLE      NOT NULL DEFAULT 20,
          food_level     INT         NOT NULL DEFAULT 20, -- hunger
          saturation     FLOAT       NOT NULL DEFAULT 5,
          exhaustion     FLOAT       NOT NULL DEFAULT 0,

          game_mode      VARCHAR(16) NULL,                -- SURVIVAL/CREATIVE/ADVENTURE/SPECTATOR
          potion_effects MEDIUMTEXT  NULL,                -- JSON with active effects
          stats_json     MEDIUMTEXT  NULL,                -- JSON dump of arbitrary stats/advancements
          last_death_loc TEXT        NULL,                -- serialized Location
          bed_spawn_loc  TEXT        NULL,                -- serialized Location

          last_update    BIGINT      NOT NULL,

          PRIMARY KEY (uuid, server_name),
          CONSTRAINT fk_state_users
            FOREIGN KEY (uuid) REFERENCES essentials_users (uuid)
            ON DELETE CASCADE,
          INDEX idx_state_last_update (last_update),
          INDEX idx_state_uuid (uuid),
          INDEX idx_state_server (server_name)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
        """;

        // registry of known servers with a master flag
        String ddlServers = """
            CREATE TABLE IF NOT EXISTS essentials_servers (
              server_name VARCHAR(64) NOT NULL PRIMARY KEY,
              is_master   BOOLEAN NOT NULL DEFAULT FALSE,
              created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

        try (Statement st = connection.createStatement()) {
            st.executeUpdate(ddlUsers);
            st.executeUpdate(ddlProfiles);
            st.executeUpdate(ddlState);
            st.executeUpdate(ddlServers);
        }
    }

    public void upsertServerRegistry(String serverName, boolean isMaster) throws SQLException {
        ensureConnected();
        String sql = """
            INSERT INTO essentials_servers (server_name, is_master)
            VALUES (?, ?)
            ON DUPLICATE KEY UPDATE
              is_master = VALUES(is_master)
            """;
        try (var ps = connection.prepareStatement(sql)) {
            ps.setString(1, serverName);
            ps.setBoolean(2, isMaster);
            ps.executeUpdate();
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

    private void ensureConnected() throws SQLException {
        if (!isConnected()) connect();
    }
}
