package org.secverse.secVersEssentialsXMySQLConnector.worker;

import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.secverse.secVersEssentialsXMySQLConnector.helper.DBCommands;
import org.secverse.secVersEssentialsXMySQLConnector.helper.EssentialsMapper;
import org.secverse.secVersEssentialsXMySQLConnector.helper.HomesCodec;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * HomeDataWorker
 *
 * Purpose:
 *   Synchronize EssentialsX homes between the server and MySQL per-server profile table.
 *
 * Join policy:
 *   - If player has no homes locally and DB has homes -> import DB -> player.
 *   - If DB has no homes and player has homes -> export player -> DB.
 *   - If both have homes and differ -> prefer DB -> player to avoid data loss.
 *
 * Live updates:
 *   - Intercepts EssentialsX home commands (/sethome, /delhome, /renamehome) and exports shortly after.
 *   - Provides a periodic safeguard flush for players marked dirty.
 *
 * Safety:
 *   - Uses only-if-newer guard in DBCommands to avoid overwriting newer data.
 *   - All Bukkit mutations happen on main thread; DB I/O runs async.
 */
public final class HomeDataWorker implements Listener {

    private final Plugin plugin;
    private final Essentials essentials;
    private final DBCommands db;
    private final String serverName;
    private final Logger logger;

    // Flush cadence for dirty players
    private final int flushIntervalSeconds;

    // Optional debounce after a command to batch multiple changes quickly
    private final int debounceTicks;

    // Player dirty flags
    private final Map<UUID, Boolean> dirty = new ConcurrentHashMap<>();

    private BukkitRunnable flushTask;

    public HomeDataWorker(Plugin plugin,
                          Essentials essentials,
                          DBCommands db,
                          String serverName,
                          int flushIntervalSeconds,
                          int debounceTicks) {
        this.plugin = plugin;
        this.essentials = essentials;
        this.db = db;
        this.serverName = serverName;
        this.flushIntervalSeconds = Math.max(5, flushIntervalSeconds);
        this.debounceTicks = Math.max(1, debounceTicks);
        this.logger = plugin.getLogger();
    }

    /**
     * Registers listeners and starts periodic async flush.
     */
    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);

        flushTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (dirty.getOrDefault(p.getUniqueId(), false)) {
                        flushHomesAsync(p);
                    }
                }
            }
        };
        flushTask.runTaskTimerAsynchronously(plugin, flushIntervalSeconds * 20L, flushIntervalSeconds * 20L);
    }

    /**
     * Unregisters listeners and stops background tasks.
     */
    public void stop() {
        HandlerList.unregisterAll(this);
        if (flushTask != null) {
            try { flushTask.cancel(); } catch (Exception ignored) {}
        }
        dirty.clear();
    }

    // ─────────────────────────── Event hooks ───────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();

        // Decide import or export based on current state vs DB
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            long now = System.currentTimeMillis();
            try {
                db.ensureServerProfile(id, serverName, now);

                DBCommands.ServerProfile sp = db.getServerProfile(id, serverName);
                String dbHomesJson = sp != null ? sp.homes : null;
                Map<String, org.bukkit.Location> dbHomes = HomesCodec.deserialize(dbHomesJson);

                Map<String, org.bukkit.Location> localHomes = EssentialsMapper.extractHomes(essentials.getUser(p));

                boolean localEmpty = localHomes.isEmpty();
                boolean dbEmpty = dbHomes.isEmpty();

                if (localEmpty && !dbEmpty) {
                    // Import DB -> Player
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        try {
                            applyHomesToPlayer(p, dbHomes);
                        } catch (Exception ex) {
                            logger.warning("[HomeDataWorker] apply DB->Player homes failed for " + p.getName() + ": " + ex.getMessage());
                        }
                    });
                    dirty.put(id, false);
                } else if (dbEmpty && !localEmpty) {
                    // Export Player -> DB
                    exportHomesAsync(p, now);
                } else if (!dbEmpty && !localEmpty) {
                    // Both have data; if not equal, DB wins
                    if (!homesEqual(dbHomes, localHomes)) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            try {
                                applyHomesToPlayer(p, dbHomes);
                            } catch (Exception ex) {
                                logger.warning("[HomeDataWorker] reconcile DB->Player homes failed for " + p.getName() + ": " + ex.getMessage());
                            }
                        });
                        dirty.put(id, false);
                    } else {
                        dirty.put(id, false);
                    }
                } else {
                    // Both empty, nothing to do
                    dirty.put(id, false);
                }
            } catch (Exception ex) {
                logger.warning("[HomeDataWorker] onJoin decision failed for " + p.getName() + ": " + ex.getMessage());
                // As a fallback, mark dirty to export whatever exists soon
                dirty.put(id, true);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> flushHomesAsync(p));
        dirty.remove(p.getUniqueId());
    }

    /**
     * Intercepts EssentialsX home commands and schedules a debounced export.
     * Commands handled: /sethome, /delhome, /renamehome
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String msg = e.getMessage();
        if (msg == null) return;
        String lower = msg.toLowerCase(Locale.ROOT).trim();

        if (lower.startsWith("/sethome") || lower.startsWith("/delhome") || lower.startsWith("/renamehome")) {
            Player p = e.getPlayer();
            UUID id = p.getUniqueId();
            dirty.put(id, true);

            // Debounce export a few ticks later to let Essentials finish its own write
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> flushHomesAsync(p), debounceTicks);
        }
    }

    // ─────────────────────────── Core logic ───────────────────────────

    /**
     * Flushes homes of a player to DB if marked dirty.
     */
    private void flushHomesAsync(Player p) {
        if (p == null || !p.isOnline()) return;
        if (!dirty.getOrDefault(p.getUniqueId(), false)) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            long now = System.currentTimeMillis();
            try {
                exportHomesAsync(p, now);
                dirty.put(p.getUniqueId(), false);
            } catch (Exception ex) {
                logger.warning("[HomeDataWorker] flush homes failed for " + p.getName() + ": " + ex.getMessage());
            }
        });
    }

    /**
     * Exports current local homes to DB using only-if-newer guard.
     */
    private void exportHomesAsync(Player p, long now) throws Exception {
        User u = essentials.getUser(p);
        Map<String, org.bukkit.Location> homes = EssentialsMapper.extractHomes(u);
        String json = HomesCodec.serialize(homes);
        db.updateHomesIfNewer(p.getUniqueId(), serverName, json, now);
    }

    /**
     * Applies a homes map to the player, replacing existing homes.
     * This runs on the main thread only.
     */
    private void applyHomesToPlayer(Player p, Map<String, org.bukkit.Location> homes) {
        User u = essentials.getUser(p);

        // Clear existing homes first
        List<String> existing = u.getHomes();
        for (String h : new ArrayList<>(existing)) {
            try { u.delHome(h); } catch (Exception ignored) {}
        }

        // Set new homes
        for (Map.Entry<String, org.bukkit.Location> e : homes.entrySet()) {
            if (e.getValue() == null) continue;
            try { u.setHome(e.getKey(), e.getValue()); } catch (Exception ignored) {}
        }

        try { u.save(); } catch (Exception ignored) {}
    }

    /**
     * Compares two homes maps by name and coordinates serialized via HomesCodec to be stable.
     */
    private boolean homesEqual(Map<String, org.bukkit.Location> a, Map<String, org.bukkit.Location> b) {
        if (a.size() != b.size()) return false;
        // Compare serialized strings for stability
        String ja = HomesCodec.serialize(a);
        String jb = HomesCodec.serialize(b);
        return Objects.equals(ja, jb);
    }
}