package org.secverse.secVersEssentialsXMySQLConnector.helper;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Objects;

public class LocationCodec {
    private static final String NULL_TOKEN = "NULL";

    private LocationCodec() { }

    /**
     * Serializes a Bukkit Location to a comma-separated string.
     *
     * @param loc Location to serialize, may be null
     * @return string representation or "NULL" if loc is null
     */
    public static String serialize(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return NULL_TOKEN;
        }
        return String.join(",",
                loc.getWorld().getName(),
                String.valueOf(loc.getX()),
                String.valueOf(loc.getY()),
                String.valueOf(loc.getZ()),
                String.valueOf(loc.getYaw()),
                String.valueOf(loc.getPitch())
        );
    }

    /**
     * Deserializes a string back into a Bukkit Location.
     *
     * @param str serialized string, may be null
     * @return Location instance or null if invalid or world not found
     */
    public static Location deserialize(String str) {
        if (str == null || str.isBlank() || NULL_TOKEN.equalsIgnoreCase(str)) {
            return null;
        }
        String[] parts = str.split(",");
        if (parts.length < 6) {
            return null;
        }
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) {
            return null;
        }
        try {
            double x = Double.parseDouble(parts[1]);
            double y = Double.parseDouble(parts[2]);
            double z = Double.parseDouble(parts[3]);
            float yaw = Float.parseFloat(parts[4]);
            float pitch = Float.parseFloat(parts[5]);
            return new Location(world, x, y, z, yaw, pitch);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
