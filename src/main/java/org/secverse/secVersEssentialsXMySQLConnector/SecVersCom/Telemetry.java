package org.secverse.secVersEssentialsXMySQLConnector.SecVersCom;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Telemetry helper for a Bukkit/Spigot/Paper plugin.
 *
 * Responsibilities:
 * - Generate and persist a unique HWID per plugin installation.
 * - Provide server name and basic metadata.
 * - Build telemetry JSON payloads.
 * - Send telemetry POST requests asynchronously using Bukkit scheduler.
 *
 * Note: This class keeps network I/O off the main thread by using Bukkit's scheduler.
 *       Telemetry is opt-in/opt-out via the plugin config (telemetry.enabled).
 */
public class Telemetry {

    private static final String HWID_FILENAME = "hwid.txt";
    private final Plugin plugin;
    private final File dataFolder;
    private final UUID hwid;
    private final String endpoint;
    private final boolean enabled;

    /**
     * Initialize Telemetry for a plugin. Will create the plugin data folder if missing
     * and persist a generated HWID in hwid.txt.
     *
     * @param plugin  your main plugin instance (for scheduler & data folder)
     */
    public Telemetry(Plugin plugin) {
        this.plugin = plugin;
        this.dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            boolean created = dataFolder.mkdirs();
            if (!created) {
                plugin.getLogger().warning("Could not create plugin data folder for telemetry persistence.");
            }
        }

        // load config values (fall back to safe defaults)
        this.enabled = plugin.getConfig().getBoolean("telemetry.enabled", true);
        this.endpoint = plugin.getConfig().getString("telemetry.endpoint", "").trim();

        // load or generate HWID
        this.hwid = loadOrCreateHwid();
    }

    /**
     * Returns the persisted HWID for this plugin installation.
     *
     * @return UUID representing the installation HWID.
     */
    public UUID getHwid() {
        return hwid;
    }

    /**
     * Returns the server name as reported by Bukkit. If that is empty or generic,
     * additional heuristics could be added.
     *
     * @return server name string
     */
    public String getServerName() {
        String name = Bukkit.getServer().getName();
        if (name == null || name.isEmpty()) {
            // fallback: try to read server.properties server-name variants or return "unknown"
            return "unknown";
        }
        return name;
    }

    /**
     * Build a simple telemetry payload. Extend the map for custom fields.
     *
     * @param additional optional extra key/value pairs to include
     * @return JSON string representing the payload
     */
    public String buildPayload(Map<String, Object> additional) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("hwid", hwid.toString());
        payload.put("serverName", getServerName());
        payload.put("pluginName", plugin.getDescription().getName());
        payload.put("pluginVersion", plugin.getDescription().getVersion());
        payload.put("timestamp", Instant.now().toString());

        if (additional != null) {
            payload.putAll(additional);
        }

        return toJson(payload);
    }

    /**
     * Send telemetry to configured endpoint asynchronously.
     * If telemetry is disabled or endpoint missing, the call returns without network IO.
     *
     * @param additional optional extra fields to include in the payload
     */
    public void sendTelemetryAsync(Map<String, Object> additional) {
        if (!enabled) {
            plugin.getLogger().info("Telemetry disabled in config; skipping send.");
            return;
        }
        if (endpoint == null || endpoint.isEmpty()) {
            plugin.getLogger().warning("Telemetry endpoint not configured; skipping send.");
            return;
        }

        final String payload = buildPayload(additional);


        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    sendPost(payload);
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to send telemetry: " + e.getMessage());
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * Sends a JSON payload to a URL using POST.
     *
     * @param jsonPayload the JSON string
     * @throws IOException on network or IO errors
     */
    private void sendPost(String jsonPayload) throws IOException {
        URL url = new URL("https://api.secvers.org/v1/telemetry/EssentailsXMySQL");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            byte[] out = jsonPayload.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(out.length);
            conn.connect();

            try (OutputStream os = conn.getOutputStream()) {
                os.write(out);
            }

            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                // read error stream for debugging
                String err = readStream(conn.getErrorStream());
                plugin.getLogger().warning("Telemetry server returned HTTP " + status + ": " + err);
            } else {
                plugin.getLogger().fine("Telemetry sent successfully.");
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Convert a Map to a minimally escaped JSON string. This avoids external libs.
     * This simple serializer supports string/number/boolean values and nested maps.
     *
     * Note: For complex payloads, prefer a proper JSON library (Gson/Jackson).
     *
     * @param map data map
     * @return JSON string
     */
    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJson(e.getKey())).append("\":");
            sb.append(valueToJson(e.getValue()));
        }
        sb.append("}");
        return sb.toString();
    }

    private static String valueToJson(Object val) {
        if (val == null) return "null";
        if (val instanceof Number || val instanceof Boolean) return val.toString();
        if (val instanceof Map) return toJson((Map<String, Object>) val);
        return "\"" + escapeJson(val.toString()) + "\"";
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String readStream(InputStream is) {
        if (is == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                out.append(line);
            }
            return out.toString();
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Try to load an existing HWID from hwid.txt or create a new UUID and persist it.
     *
     * @return UUID used as hwid
     */
    private UUID loadOrCreateHwid() {
        File file = new File(dataFolder, HWID_FILENAME);
        if (file.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                String line = br.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    try {
                        return UUID.fromString(line.trim());
                    } catch (IllegalArgumentException ignore) {
                        // fall through -> regenerate
                    }
                }
            } catch (IOException ignored) {
                // ignore and generate new
            }
        }

        UUID newId = UUID.randomUUID();
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(file))) {
            bw.write(newId.toString());
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to persist hwid to file: " + e.getMessage());
        }
        return newId;
    }
}
