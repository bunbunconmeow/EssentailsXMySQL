package org.secverse.secVersEssentialsXMySQLConnector.SecVersCom;


import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UpdateChecker that fetches latest version from hardcoded secvers API.
 */
public final class UpdateChecker {

    // Hardcoded constants
    private static final String ENDPOINT_URL = "https://api.secvers.org/v1/plugin/EssentailsXMySQL";
    private static final String DOWNLOAD_URL = "https://secvers.org/";
    private static final Pattern SIMPLE_JSON_VERSION = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");

    private final Plugin plugin;

    public UpdateChecker(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * Run a single async check for update.
     */
    public void checkNowAsync() {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    String remoteVersion = fetchRemoteVersion(ENDPOINT_URL);
                    if (remoteVersion == null || remoteVersion.isEmpty()) {
                        plugin.getLogger().warning("[Update] Could not parse remote version.");
                        return;
                    }

                    String localVersion = plugin.getDescription().getVersion();
                    if (isOutdated(localVersion, remoteVersion)) {
                        broadcastUpdate(remoteVersion, localVersion);
                    } else {
                        plugin.getLogger().info("[Update] Plugin is up-to-date. Local " + localVersion + ", Remote " + remoteVersion);
                    }
                } catch (Exception ex) {
                    plugin.getLogger().warning("[Update] Failed to check updates: " + ex.getMessage());
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    private String fetchRemoteVersion(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(6000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");

        int code = conn.getResponseCode();
        if (code != 200) throw new IllegalStateException("HTTP " + code);

        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String ln;
            while ((ln = br.readLine()) != null) sb.append(ln);
            String json = sb.toString();
            Matcher m = SIMPLE_JSON_VERSION.matcher(json);
            if (m.find()) return m.group(1).trim();
            return null;
        } finally {
            conn.disconnect();
        }
    }

    public static boolean isOutdated(String local, String remote) {
        return !normalize(local).equals(normalize(remote));
    }

    private static String normalize(String v) {
        if (v == null) return "0";
        String core = v.split("[+-]")[0];
        core = core.replaceAll("[^0-9.]", "");
        return core.isEmpty() ? "0" : core;
    }

    private void broadcastUpdate(String remoteVersion, String localVersion) {
        Server server = Bukkit.getServer();
        String legacy = "§c§l[SecVers] New Update available: " + remoteVersion
                + " (current " + localVersion + "). Download: " + DOWNLOAD_URL;
        Bukkit.broadcastMessage(legacy);
        server.getConsoleSender().sendMessage(legacy);
    }
}
