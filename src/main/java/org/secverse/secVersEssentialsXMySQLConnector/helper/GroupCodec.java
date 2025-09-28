package org.secverse.secVersEssentialsXMySQLConnector.helper;

import com.google.gson.Gson;
import com.google.gson.JsonArray;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;


public class GroupCodec {

    private static final Gson GSON = new Gson();

    /**
     * Allowed characters pattern after normalization.
     * Letters, digits, dot, underscore, hyphen.
     */
    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._-]+");

    private GroupCodec() { }

    /**
     * Serializes a single group to JSON array form.
     * Returns "[]" for null or empty input.
     */
    public static String serialize(String group) {
        if (group == null) return "[]";
        String g = normalize(group);
        if (g == null) return "[]";
        JsonArray arr = new JsonArray();
        arr.add(g);
        return GSON.toJson(arr);
    }

    /**
     * Serializes multiple groups to a JSON array string.
     * Duplicates are removed, order of first appearance is preserved.
     * Returns "[]" for null or empty input.
     */
    public static String serialize(Collection<String> groups) {
        Set<String> norm = normalizeDistinct(groups);
        JsonArray arr = new JsonArray();
        for (String g : norm) arr.add(g);
        return GSON.toJson(arr);
    }

    /**
     * Deserializes a JSON array string into a distinct ordered set of groups.
     * Returns an empty set if input is null, blank or invalid.
     */
    public static Set<String> deserializeToSet(String json) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (json == null || json.isBlank()) return out;
        try {
            JsonArray arr = GSON.fromJson(json, JsonArray.class);
            if (arr == null) return out;
            for (int i = 0; i < arr.size(); i++) {
                String raw = arr.get(i).getAsString();
                String g = normalize(raw);
                if (g != null) out.add(g);
            }
        } catch (Exception ignored) {
            // fall through to empty
        }
        return out;
    }

    /**
     * Returns the primary group from a JSON array string.
     * Primary is the first valid group after normalization.
     * Returns null if none found.
     */
    public static String deserializeToPrimary(String json) {
        for (String g : deserializeToSet(json)) {
            return g;
        }
        return null;
    }

    /**
     * Convenience: convert a set of groups to CSV form "group1,group2,...".
     * Useful for logs or legacy storage. Returns empty string for empty input.
     */
    public static String toCsv(Collection<String> groups) {
        Set<String> norm = normalizeDistinct(groups);
        if (norm.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String g : norm) {
            if (sb.length() > 0) sb.append(',');
            sb.append(g);
        }
        return sb.toString();
    }

    /**
     * Convenience: parse CSV into an ordered distinct set.
     * Trims entries and normalizes names. Invalid entries are skipped.
     */
    public static Set<String> fromCsv(String csv) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) return out;
        String[] parts = csv.split(",");
        for (String p : parts) {
            String g = normalize(p);
            if (g != null) out.add(g);
        }
        return out;
    }

    /**
     * Normalizes a single group name:
     * - trim
     * - collapse inner whitespace sequences to single underscore
     * - validate against allowed pattern
     * - returns null if empty or invalid
     */
    public static String normalize(String group) {
        if (group == null) return null;
        String g = group.trim();
        if (g.isEmpty()) return null;
        g = g.replaceAll("\\s+", "_");
        if (!ALLOWED.matcher(g).matches()) return null;
        if (g.length() > 64) g = g.substring(0, 64);
        return g;
    }

    /**
     * Normalizes a collection of groups into a distinct ordered set.
     * Nulls and invalid entries are dropped.
     */
    public static LinkedHashSet<String> normalizeDistinct(Collection<String> groups) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (groups == null) return out;
        for (String s : groups) {
            String g = normalize(s);
            if (g != null) out.add(g);
        }
        return out;
    }

    /**
     * Merges two JSON arrays of groups, preserving order with deduplication.
     * Values from primaryJson come first, then from secondaryJson if not present.
     * Returns JSON array string.
     */
    public static String mergeJsonGroups(String primaryJson, String secondaryJson) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        merged.addAll(deserializeToSet(primaryJson));
        for (String g : deserializeToSet(secondaryJson)) {
            merged.add(g);
        }
        return serialize(merged);
    }

    /**
     * Checks if two JSON arrays represent the same set and order of groups.
     */
    public static boolean equalsJson(String a, String b) {
        return Objects.equals(serialize(deserializeToSet(a)), serialize(deserializeToSet(b)));
    }
}
