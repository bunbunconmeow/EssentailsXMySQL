package org.secverse.secVersEssentialsXMySQLConnector.helper;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.Location;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;


public class HomesCodec {
    private static final Gson gson = new Gson();

    private HomesCodec() { }

    /**
     * Serializes a map of homes to JSON.
     *
     * @param homes map of home name -> Location
     * @return JSON string (never null, "{}" if empty)
     */
    public static String serialize(Map<String, Location> homes) {
        if (homes == null || homes.isEmpty()) {
            return "{}";
        }
        JsonObject obj = new JsonObject();
        for (Map.Entry<String, Location> e : homes.entrySet()) {
            String homeName = e.getKey();
            Location loc = e.getValue();
            obj.addProperty(homeName, LocationCodec.serialize(loc));
        }
        return gson.toJson(obj);
    }

    /**
     * Deserializes JSON back into a map of homes.
     *
     * @param json JSON string, may be null
     * @return map of home name -> Location
     */
    public static Map<String, Location> deserialize(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Location> map = new HashMap<>();
            JsonObject obj = gson.fromJson(json, JsonObject.class);
            for (String key : obj.keySet()) {
                String serializedLoc = obj.get(key).getAsString();
                Location loc = LocationCodec.deserialize(serializedLoc);
                if (loc != null) {
                    map.put(key, loc);
                }
            }
            return map;
        } catch (Exception ex) {
            return Collections.emptyMap();
        }
    }
}
