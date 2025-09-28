package org.secverse.secVersEssentialsXMySQLConnector.helper;

import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.User;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.*;


public class EssentialsMapper {
    private EssentialsMapper() { }
    public static DBCommands.ServerProfile toServerProfile(Essentials essentials,
                                                           Player player,
                                                           String serverName,
                                                           long nowMillis) {
        User user = essentials.getUser(player);

        DBCommands.ServerProfile sp = new DBCommands.ServerProfile();
        sp.uuid = player.getUniqueId();
        sp.serverName = serverName;
        sp.groupName = safeGroup(user);
        sp.lastLocation = LocationCodec.serialize(safeLocation(user));
        sp.homes = HomesCodec.serialize(extractHomes(user));
        sp.lastUpdate = nowMillis;
        return sp;
    }

    public static void applyServerProfile(Essentials essentials,
                                          Player player,
                                          DBCommands.ServerProfile profile) {
        if (profile == null) return;

        User user = essentials.getUser(player);

        // Apply last location
        Location last = LocationCodec.deserialize(profile.lastLocation);
        if (last != null) {
            try {
                user.setLastLocation(last);
            } catch (Exception ignored) {}
        }

        // Apply homes: replace-all strategy
        try {
            // Clear existing homes
            List<String> current = user.getHomes();
            for (String h : new ArrayList<>(current)) {
                try {
                    user.delHome(h);
                } catch (Exception ignored) {}
            }

            // Add homes from snapshot
            Map<String, Location> homes = HomesCodec.deserialize(profile.homes);
            for (Map.Entry<String, Location> e : homes.entrySet()) {
                if (e.getValue() != null) {
                    try {
                        user.setHome(e.getKey(), e.getValue());
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}

        try {
            user.save();
        } catch (Exception ignored) {}
    }

    public static Map<String, Object> toDebugMap(DBCommands.ServerProfile profile) {
        Map<String, Object> out = new HashMap<>();
        if (profile == null) return out;
        out.put("server", profile.serverName);
        out.put("groupName", profile.groupName);
        out.put("lastLocation", profile.lastLocation);
        out.put("homesCount", HomesCodec.deserialize(profile.homes).size());
        out.put("lastUpdate", profile.lastUpdate);
        return out;
    }

    /**
     * Extracts homes from an Essentials user.
     */
    public static Map<String, Location> extractHomes(User user) {
        Map<String, Location> homes = new HashMap<>();
        if (user == null) return homes;
        try {
            List<String> names = user.getHomes();
            for (String name : names) {
                try {
                    Location loc = user.getHome(name);
                    if (loc != null) homes.put(name, loc);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return homes;
    }

    public static String safeGroup(User user) {
        try {
            String g = user.getGroup();
            return (g != null && !g.isBlank()) ? g : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static Location safeLocation(User user) {
        try {
            Location loc = user.getLocation();
            if (loc != null) return loc;
        } catch (Exception ignored) {}
        try {
            Player p = user.getBase();
            if (p != null) return p.getLocation();
        } catch (Exception ignored) {}
        return null;
    }

    public static DBCommands.ServerProfile newEmptyProfile(UUID uuid, String serverName, long nowMillis) {
        DBCommands.ServerProfile sp = new DBCommands.ServerProfile();
        sp.uuid = uuid;
        sp.serverName = serverName;
        sp.groupName = null;
        sp.lastLocation = null;
        sp.homes = "{}";
        sp.lastUpdate = nowMillis;
        return sp;
    }
}
