package org.secverse.secVersEssentialsXMySQLConnector.helper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DBCommands {
    private final Connection conn;
    public DBCommands(Connection conn) { this.conn = conn; }

    public static final class GlobalUser {
        public UUID uuid;
        public String name;
        public double balance;
        public long lastUpdate;
    }
    public static final class ServerProfile {
        public UUID uuid;
        public String serverName;
        public String groupName;     // nullable
        public String lastLocation;  // serialized location, nullable
        public String homes;         // serialized homes, nullable
        public long lastUpdate;
    }

    public static final class UserState {
        public UUID uuid;
        public String serverName;

        public byte[] invMain;      // MEDIUMBLOB
        public byte[] invOffhand;   // BLOB
        public byte[] invArmor;     // BLOB
        public byte[] enderChest;   // MEDIUMBLOB

        public int xpLevel;
        public int xpTotal;
        public float xpProgress;

        public double health;
        public double maxHealth;

        public int foodLevel;
        public float saturation;
        public float exhaustion;

        public String gameMode;        // nullable
        public String potionEffects;   // JSON, nullable
        public String statsJson;       // JSON, nullable
        public String lastDeathLoc;    // serialized location, nullable
        public String bedSpawnLoc;     // serialized location, nullable

        public long lastUpdate;
    }

    public void ensureGlobalUser(UUID uuid, String name, long newTimestamp) throws SQLException {
        final String sql = """
            INSERT IGNORE INTO essentials_users (uuid, name, balance, last_update)
            VALUES (?, ?, 0, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setLong(3, newTimestamp);
            ps.executeUpdate();
        }
    }

    public boolean upsertGlobalUserIfNewer(UUID uuid, String name, double balance, long newTimestamp) throws SQLException {
        final String sql = """
            INSERT INTO essentials_users (uuid, name, balance, last_update)
            VALUES (?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              name = VALUES(name),
              balance = IF(VALUES(last_update) > last_update, VALUES(balance), balance),
              last_update = GREATEST(last_update, VALUES(last_update))
            """;
        // The ON DUPLICATE branch always runs for existing rows, but balance only changes if timestamp advanced.
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setBigDecimal(3, new BigDecimal(balance).setScale(2, RoundingMode.HALF_UP));
            ps.setLong(4, newTimestamp);
            int affected = ps.executeUpdate();
            if (affected == 1) return true;       // inserted
            // For existing row, we must check whether last_update advanced.
            GlobalUser gu = getGlobalUser(uuid);
            return gu != null && gu.lastUpdate == newTimestamp;
        }
    }

    public boolean updateBalanceIfNewer(UUID uuid, double balance, long newTimestamp) throws SQLException {
        final String sql = """
            UPDATE essentials_users
            SET balance=?, last_update=?
            WHERE uuid=? AND (? > last_update)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBigDecimal(1, new BigDecimal(balance).setScale(2, RoundingMode.HALF_UP));
            ps.setLong(2, newTimestamp);
            ps.setString(3, uuid.toString());
            ps.setLong(4, newTimestamp);
            return ps.executeUpdate() > 0;
        }
    }

    public GlobalUser getGlobalUser(UUID uuid) throws SQLException {
        final String sql = "SELECT uuid, name, balance, last_update FROM essentials_users WHERE uuid = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                GlobalUser gu = new GlobalUser();
                gu.uuid = uuid;
                gu.name = rs.getString("name");
                gu.balance = rs.getDouble("balance");
                gu.lastUpdate = rs.getLong("last_update");
                return gu;
            }
        }
    }

    public void ensureServerProfile(UUID uuid, String serverName, long newTimestamp) throws SQLException {
        final String sql = """
            INSERT IGNORE INTO essentials_user_profiles (uuid, server_name, groupname, last_location, homes, last_update)
            VALUES (?, ?, NULL, NULL, NULL, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, serverName);
            ps.setLong(3, newTimestamp);
            ps.executeUpdate();
        }
    }

    public boolean upsertServerProfileIfNewer(UUID uuid,
                                              String serverName,
                                              String groupName,
                                              String lastLocation,
                                              String homes,
                                              long newTimestamp) throws SQLException {
        final String sql = """
            INSERT INTO essentials_user_profiles (uuid, server_name, groupname, last_location, homes, last_update)
            VALUES (?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              groupname     = IF(VALUES(last_update) > last_update, VALUES(groupname), groupname),
              last_location = IF(VALUES(last_update) > last_update, VALUES(last_location), last_location),
              homes         = IF(VALUES(last_update) > last_update, VALUES(homes), homes),
              last_update   = GREATEST(last_update, VALUES(last_update))
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, serverName);
            ps.setString(3, groupName);
            ps.setString(4, lastLocation);
            ps.setString(5, homes);
            ps.setLong(6, newTimestamp);
            int affected = ps.executeUpdate();
            if (affected == 1) return true; // inserted
            ServerProfile sp = getServerProfile(uuid, serverName);
            return sp != null && sp.lastUpdate == newTimestamp;
        }
    }

    public boolean updateHomesIfNewer(UUID uuid, String serverName, String homes, long newTimestamp) throws SQLException {
        final String sql = """
            UPDATE essentials_user_profiles
            SET homes=?, last_update=?
            WHERE uuid=? AND server_name=? AND (? > last_update)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, homes);
            ps.setLong(2, newTimestamp);
            ps.setString(3, uuid.toString());
            ps.setString(4, serverName);
            ps.setLong(5, newTimestamp);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean updateGroupIfNewer(UUID uuid, String serverName, String groupName, long newTimestamp) throws SQLException {
        final String sql = """
            UPDATE essentials_user_profiles
            SET groupname=?, last_update=?
            WHERE uuid=? AND server_name=? AND (? > last_update)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, groupName);
            ps.setLong(2, newTimestamp);
            ps.setString(3, uuid.toString());
            ps.setString(4, serverName);
            ps.setLong(5, newTimestamp);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean updateLastLocationIfNewer(UUID uuid, String serverName, String lastLocation, long newTimestamp) throws SQLException {
        final String sql = """
            UPDATE essentials_user_profiles
            SET last_location=?, last_update=?
            WHERE uuid=? AND server_name=? AND (? > last_update)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, lastLocation);
            ps.setLong(2, newTimestamp);
            ps.setString(3, uuid.toString());
            ps.setString(4, serverName);
            ps.setLong(5, newTimestamp);
            return ps.executeUpdate() > 0;
        }
    }

    public ServerProfile getServerProfile(UUID uuid, String serverName) throws SQLException {
        final String sql = """
            SELECT uuid, server_name, groupname, last_location, homes, last_update
            FROM essentials_user_profiles
            WHERE uuid = ? AND server_name = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, serverName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                ServerProfile sp = new ServerProfile();
                sp.uuid = uuid;
                sp.serverName = serverName;
                sp.groupName = rs.getString("groupname");
                sp.lastLocation = rs.getString("last_location");
                sp.homes = rs.getString("homes");
                sp.lastUpdate = rs.getLong("last_update");
                return sp;
            }
        }
    }
    public List<ServerProfile> listServerProfiles(UUID uuid) throws SQLException {
        final String sql = """
            SELECT server_name, groupname, last_location, homes, last_update
            FROM essentials_user_profiles
            WHERE uuid = ?
            ORDER BY server_name
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<ServerProfile> out = new ArrayList<>();
                while (rs.next()) {
                    ServerProfile sp = new ServerProfile();
                    sp.uuid = uuid;
                    sp.serverName = rs.getString("server_name");
                    sp.groupName = rs.getString("groupname");
                    sp.lastLocation = rs.getString("last_location");
                    sp.homes = rs.getString("homes");
                    sp.lastUpdate = rs.getLong("last_update");
                    out.add(sp);
                }
                return out;
            }
        }
    }

    /**
     * Deletes a per-server profile (uuid, serverName). Returns true if a row was removed.
     */
    public boolean deleteServerProfile(UUID uuid, String serverName) throws SQLException {
        final String sql = "DELETE FROM essentials_user_profiles WHERE uuid=? AND server_name=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, serverName);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Ensures a per-server player state row exists. No overwrite of existing data.
     * Initializes with defaults and last_update = newTimestamp if inserted.
     */
    public void ensureUserState(UUID uuid, String serverName, long newTimestamp) throws SQLException {
        final String sql = """
        INSERT IGNORE INTO essentials_user_state (
          uuid, server_name,
          inv_main, inv_offhand, inv_armor, ender_chest,
          xp_level, xp_total, xp_progress,
          health, max_health,
          food_level, saturation, exhaustion,
          game_mode, potion_effects, stats_json, last_death_loc, bed_spawn_loc,
          last_update
        ) VALUES (?, ?, NULL, NULL, NULL, NULL,
                  0, 0, 0,
                  20, 20,
                  20, 5, 0,
                  NULL, NULL, NULL, NULL, NULL,
                  ?)
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, serverName);
            ps.setLong(3, newTimestamp);
            ps.executeUpdate();
        }
    }


    /**
     * Upserts the entire player state for (uuid, serverName) if and only if newTimestamp is strictly newer.
     * Returns true if the row was inserted or updated, false if skipped due to stale timestamp.
     */
    public boolean upsertUserStateIfNewer(UserState s, long newTimestamp) throws SQLException {
        final String sql = """
        INSERT INTO essentials_user_state (
          uuid, server_name,
          inv_main, inv_offhand, inv_armor, ender_chest,
          xp_level, xp_total, xp_progress,
          health, max_health,
          food_level, saturation, exhaustion,
          game_mode, potion_effects, stats_json, last_death_loc, bed_spawn_loc,
          last_update
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE
          inv_main       = IF(VALUES(last_update) > last_update, VALUES(inv_main),       inv_main),
          inv_offhand    = IF(VALUES(last_update) > last_update, VALUES(inv_offhand),    inv_offhand),
          inv_armor      = IF(VALUES(last_update) > last_update, VALUES(inv_armor),      inv_armor),
          ender_chest    = IF(VALUES(last_update) > last_update, VALUES(ender_chest),    ender_chest),
          xp_level       = IF(VALUES(last_update) > last_update, VALUES(xp_level),       xp_level),
          xp_total       = IF(VALUES(last_update) > last_update, VALUES(xp_total),       xp_total),
          xp_progress    = IF(VALUES(last_update) > last_update, VALUES(xp_progress),    xp_progress),
          health         = IF(VALUES(last_update) > last_update, VALUES(health),         health),
          max_health     = IF(VALUES(last_update) > last_update, VALUES(max_health),     max_health),
          food_level     = IF(VALUES(last_update) > last_update, VALUES(food_level),     food_level),
          saturation     = IF(VALUES(last_update) > last_update, VALUES(saturation),     saturation),
          exhaustion     = IF(VALUES(last_update) > last_update, VALUES(exhaustion),     exhaustion),
          game_mode      = IF(VALUES(last_update) > last_update, VALUES(game_mode),      game_mode),
          potion_effects = IF(VALUES(last_update) > last_update, VALUES(potion_effects), potion_effects),
          stats_json     = IF(VALUES(last_update) > last_update, VALUES(stats_json),     stats_json),
          last_death_loc = IF(VALUES(last_update) > last_update, VALUES(last_death_loc), last_death_loc),
          bed_spawn_loc  = IF(VALUES(last_update) > last_update, VALUES(bed_spawn_loc),  bed_spawn_loc),
          last_update    = GREATEST(last_update, VALUES(last_update))
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, s.uuid.toString());
            ps.setString(i++, s.serverName);
            ps.setBytes(i++, s.invMain);
            ps.setBytes(i++, s.invOffhand);
            ps.setBytes(i++, s.invArmor);
            ps.setBytes(i++, s.enderChest);
            ps.setInt(i++, s.xpLevel);
            ps.setInt(i++, s.xpTotal);
            ps.setFloat(i++, s.xpProgress);
            ps.setDouble(i++, s.health);
            ps.setDouble(i++, s.maxHealth);
            ps.setInt(i++, s.foodLevel);
            ps.setFloat(i++, s.saturation);
            ps.setFloat(i++, s.exhaustion);
            ps.setString(i++, s.gameMode);
            ps.setString(i++, s.potionEffects);
            ps.setString(i++, s.statsJson);
            ps.setString(i++, s.lastDeathLoc);
            ps.setString(i++, s.bedSpawnLoc);
            ps.setLong(i++, newTimestamp);

            int affected = ps.executeUpdate();
            if (affected == 1) return true; // insert
            UserState loaded = getUserState(s.uuid, s.serverName);
            return loaded != null && loaded.lastUpdate >= newTimestamp;
        }
    }

    public boolean updateInventoryIfNewer(UUID uuid,
                                          String serverName,
                                          byte[] invMain,
                                          byte[] invOffhand,
                                          byte[] invArmor,
                                          byte[] enderChest,
                                          long newTimestamp) throws SQLException {
        final String sql = """
        UPDATE essentials_user_state
        SET inv_main=?, inv_offhand=?, inv_armor=?, ender_chest=?, last_update=?
        WHERE uuid=? AND server_name=? AND (? > last_update)
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBytes(1, invMain);
            ps.setBytes(2, invOffhand);
            ps.setBytes(3, invArmor);
            ps.setBytes(4, enderChest);
            ps.setLong(5, newTimestamp);
            ps.setString(6, uuid.toString());
            ps.setString(7, serverName);
            ps.setLong(8, newTimestamp);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean updateXpIfNewer(UUID uuid,
                                   String serverName,
                                   int xpLevel,
                                   int xpTotal,
                                   float xpProgress,
                                   long newTimestamp) throws SQLException {
        final String sql = """
        UPDATE essentials_user_state
        SET xp_level=?, xp_total=?, xp_progress=?, last_update=?
        WHERE uuid=? AND server_name=? AND (? > last_update)
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, xpLevel);
            ps.setInt(2, xpTotal);
            ps.setFloat(3, xpProgress);
            ps.setLong(4, newTimestamp);
            ps.setString(5, uuid.toString());
            ps.setString(6, serverName);
            ps.setLong(7, newTimestamp);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean updateVitalsIfNewer(UUID uuid,
                                       String serverName,
                                       double health,
                                       double maxHealth,
                                       int foodLevel,
                                       float saturation,
                                       float exhaustion,
                                       long newTimestamp) throws SQLException {
        final String sql = """
        UPDATE essentials_user_state
        SET health=?, max_health=?, food_level=?, saturation=?, exhaustion=?, last_update=?
        WHERE uuid=? AND server_name=? AND (? > last_update)
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, health);
            ps.setDouble(2, maxHealth);
            ps.setInt(3, foodLevel);
            ps.setFloat(4, saturation);
            ps.setFloat(5, exhaustion);
            ps.setLong(6, newTimestamp);
            ps.setString(7, uuid.toString());
            ps.setString(8, serverName);
            ps.setLong(9, newTimestamp);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean updateMetadataIfNewer(UUID uuid,
                                         String serverName,
                                         String gameMode,
                                         String potionEffectsJson,
                                         String statsJson,
                                         String lastDeathLoc,
                                         String bedSpawnLoc,
                                         long newTimestamp) throws SQLException {
        final String sql = """
        UPDATE essentials_user_state
        SET game_mode=?, potion_effects=?, stats_json=?, last_death_loc=?, bed_spawn_loc=?, last_update=?
        WHERE uuid=? AND server_name=? AND (? > last_update)
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, gameMode);
            ps.setString(2, potionEffectsJson);
            ps.setString(3, statsJson);
            ps.setString(4, lastDeathLoc);
            ps.setString(5, bedSpawnLoc);
            ps.setLong(6, newTimestamp);
            ps.setString(7, uuid.toString());
            ps.setString(8, serverName);
            ps.setLong(9, newTimestamp);
            return ps.executeUpdate() > 0;
        }
    }


    /**
     * Reads a complete player state row or null if not found.
     */
    public UserState getUserState(UUID uuid, String serverName) throws SQLException {
        final String sql = """
        SELECT
          inv_main, inv_offhand, inv_armor, ender_chest,
          xp_level, xp_total, xp_progress,
          health, max_health,
          food_level, saturation, exhaustion,
          game_mode, potion_effects, stats_json,
          last_death_loc, bed_spawn_loc,
          last_update
        FROM essentials_user_state
        WHERE uuid = ? AND server_name = ?
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, serverName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                UserState s = new UserState();
                s.uuid = uuid;
                s.serverName = serverName;

                s.invMain = rs.getBytes("inv_main");
                s.invOffhand = rs.getBytes("inv_offhand");
                s.invArmor = rs.getBytes("inv_armor");
                s.enderChest = rs.getBytes("ender_chest");

                s.xpLevel = rs.getInt("xp_level");
                s.xpTotal = rs.getInt("xp_total");
                s.xpProgress = rs.getFloat("xp_progress");

                s.health = rs.getDouble("health");
                s.maxHealth = rs.getDouble("max_health");

                s.foodLevel = rs.getInt("food_level");
                s.saturation = rs.getFloat("saturation");
                s.exhaustion = rs.getFloat("exhaustion");

                s.gameMode = rs.getString("game_mode");
                s.potionEffects = rs.getString("potion_effects");
                s.statsJson = rs.getString("stats_json");
                s.lastDeathLoc = rs.getString("last_death_loc");
                s.bedSpawnLoc = rs.getString("bed_spawn_loc");

                s.lastUpdate = rs.getLong("last_update");
                return s;
            }
        }
    }

    /**
     * Lists available server names for which a state exists for this player.
     */
    public List<String> listUserStateServers(UUID uuid) throws SQLException {
        final String sql = """
        SELECT server_name
        FROM essentials_user_state
        WHERE uuid = ?
        ORDER BY server_name
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) out.add(rs.getString(1));
                return out;
            }
        }
    }

    /**
     * Deletes one per-server state row.
     * Returns true if a row was removed.
     */
    public boolean deleteUserState(UUID uuid, String serverName) throws SQLException {
        final String sql = "DELETE FROM essentials_user_state WHERE uuid=? AND server_name=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, serverName);
            return ps.executeUpdate() > 0;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Server registry helpers
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Upserts a server registry entry. Useful for marking a master server externally.
     */
    public void upsertServerRegistry(String serverName, boolean isMaster) throws SQLException {
        final String sql = """
            INSERT INTO essentials_servers (server_name, is_master)
            VALUES (?, ?)
            ON DUPLICATE KEY UPDATE
              is_master = VALUES(is_master)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, serverName);
            ps.setBoolean(2, isMaster);
            ps.executeUpdate();
        }
    }

    /**
     * Returns true if serverName is currently marked as master.
     */
    public boolean isMasterServer(String serverName) throws SQLException {
        final String sql = "SELECT is_master FROM essentials_servers WHERE server_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, serverName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                return rs.getBoolean(1);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Transaction helpers
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Runs the given work inside a transaction with auto-commit restore.
     */
    public <T> T inTransaction(SqlWork<T> work) throws SQLException {
        boolean prev = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            T result = work.run();
            conn.commit();
            return result;
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(prev);
        }
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T run() throws SQLException;
    }

}