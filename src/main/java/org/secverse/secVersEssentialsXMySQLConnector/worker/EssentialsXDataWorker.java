package org.secverse.secVersEssentialsXMySQLConnector.worker;

import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.User;
import net.ess3.api.events.UserBalanceUpdateEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.secverse.secVersEssentialsXMySQLConnector.helper.DBCommands;
import org.secverse.secVersEssentialsXMySQLConnector.helper.LocationCodec;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class EssentialsXDataWorker implements Listener {

    private final Plugin plugin;
    private final Essentials essentials;
    private final DBCommands db;
    private final String serverName;
    private final boolean balanceWriteEnabled;
    private final int flushIntervalSeconds;

    private final Logger logger;

    private final ConcurrentHashMap<UUID, DirtyBits> dirty = new ConcurrentHashMap<>();
    private BukkitRunnable periodicFlush;

    public EssentialsXDataWorker(Plugin plugin,
                                 Essentials essentials,
                                 DBCommands db,
                                 String serverName,
                                 boolean balanceWriteEnabled,
                                 int flushIntervalSeconds) {
        this.plugin = Objects.requireNonNull(plugin);
        this.essentials = Objects.requireNonNull(essentials);
        this.db = Objects.requireNonNull(db);
        this.serverName = Objects.requireNonNull(serverName);
        this.balanceWriteEnabled = balanceWriteEnabled;
        this.flushIntervalSeconds = Math.max(5, flushIntervalSeconds);
        this.logger = plugin.getLogger();
    }

    /**
     * Starts the worker and registers listeners.
     */
    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);

        periodicFlush = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    flushIfDirtyAsync(p.getUniqueId());
                }
            }
        };
        periodicFlush.runTaskTimerAsynchronously(plugin, flushIntervalSeconds * 20L, flushIntervalSeconds * 20L);
    }

    /**
     * Stops the worker and unregisters listeners.
     */
    public void stop() {
        HandlerList.unregisterAll(this);
        if (periodicFlush != null) {
            try { periodicFlush.cancel(); } catch (Exception ignored) {}
        }
        dirty.clear();
    }

    // ─────────────────────────── Events ───────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        dirty.put(id, new DirtyBits().markAll());

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            long now = System.currentTimeMillis();
            try {
                db.ensureGlobalUser(id, p.getName(), now);
                db.ensureServerProfile(id, serverName, now);

                DBCommands.GlobalUser gu = db.getGlobalUser(id);
                DBCommands.ServerProfile sp = db.getServerProfile(id, serverName);

                // Local state
                User u = essentials.getUser(p);
                String localGroup = safeGroup(u);
                String localLastLoc = LocationCodec.serialize(safeLastLocation(u, p));

                boolean localProfileEmpty = isProfileEmpty(localGroup, localLastLoc);
                boolean dbProfileEmpty = isProfileEmpty(sp != null ? sp.groupName : null,
                        sp != null ? sp.lastLocation : null);

                // Balance handling
                if (!balanceWriteEnabled && gu != null) {
                    // Import DB balance into Essentials if this server is not allowed to write
                    BigDecimal bal = BigDecimal.valueOf(gu.balance);
                    Bukkit.getScheduler().runTask(plugin, () -> setEssentialsBalanceSafe(u, bal));
                }

                // Profile reconciliation
                if (localProfileEmpty && !dbProfileEmpty) {
                    // Import DB -> Player
                    Bukkit.getScheduler().runTask(plugin, () -> applyProfileToPlayer(u, sp));
                    dirty.put(id, new DirtyBits()); // clean
                } else if (dbProfileEmpty && !localProfileEmpty) {
                    // Export Player -> DB
                    exportProfileAsync(p, now);
                } else if (!dbProfileEmpty && !localProfileEmpty) {
                    // Both have data; if not equal, DB wins
                    if (!profileEquals(localGroup, localLastLoc, sp.groupName, sp.lastLocation)) {
                        Bukkit.getScheduler().runTask(plugin, () -> applyProfileToPlayer(u, sp));
                        dirty.put(id, new DirtyBits());
                    } else {
                        dirty.put(id, new DirtyBits());
                    }
                } else {
                    // Both empty; keep a minimal export soon to stamp timestamps and name
                    exportProfileAsync(p, now);
                }
            } catch (Exception ex) {
                logger.warning("[EssentialsXDataWorker] onJoin sync failed for " + p.getName() + ": " + ex.getMessage());
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> flushNow(p));
        dirty.remove(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        markLastLocationDirty(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRespawn(PlayerRespawnEvent e) {
        markLastLocationDirty(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        markLastLocationDirty(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBalanceChange(UserBalanceUpdateEvent e) {
        if (!balanceWriteEnabled) return;
        Player p = e.getPlayer();
        if (p == null) return;
        dirty.compute(p.getUniqueId(), (k, v) -> (v == null ? new DirtyBits() : v).balance(true));
    }

    // ─────────────────────────── Dirty bits ───────────────────────────

    private void markLastLocationDirty(Player p) {
        dirty.compute(p.getUniqueId(), (k, v) -> (v == null ? new DirtyBits() : v).lastLocation(true));
    }

    // ─────────────────────────── Flush logic ───────────────────────────

    private void flushIfDirtyAsync(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        if (p == null) return;
        DirtyBits bits = dirty.get(uuid);
        if (bits == null || bits.isClean()) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                flushBits(p, bits);
            } catch (Exception ex) {
                logger.warning("[EssentialsXDataWorker] flush failed for " + p.getName() + ": " + ex.getMessage());
            }
        });
    }

    private void flushNow(Player p) {
        DirtyBits bits = dirty.get(p.getUniqueId());
        if (bits == null) bits = new DirtyBits().markAll();
        try {
            flushBits(p, bits);
        } catch (Exception ex) {
            logger.warning("[EssentialsXDataWorker] final flush failed for " + p.getName() + ": " + ex.getMessage());
        }
    }

    private void flushBits(Player p, DirtyBits bits) throws Exception {
        long now = System.currentTimeMillis();
        UUID id = p.getUniqueId();
        User u = essentials.getUser(p);

        // Name is global; always keep it fresh
        db.upsertGlobalUserIfNewer(id, p.getName(), getEssentialsBalanceDouble(u), now);

        // Balance if allowed
        if (balanceWriteEnabled && bits.balance) {
            db.updateBalanceIfNewer(id, getEssentialsBalanceDouble(u), now);
        }

        // Last location
        if (bits.lastLocation) {
            String serialized = LocationCodec.serialize(safeLastLocation(u, p));
            db.updateLastLocationIfNewer(id, serverName, serialized, now);
        }

        // Group: poll current primary group and write if changed
        if (bits.group) {
            String grp = safeGroup(u);
            db.updateGroupIfNewer(id, serverName, grp, now);
        }

        // Clean flags after attempts
        dirty.put(id, new DirtyBits());
    }

    // ─────────────────────────── Import / Export helpers ───────────────────────────

    private void exportProfileAsync(Player p, long now) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                UUID id = p.getUniqueId();
                User u = essentials.getUser(p);

                // Global name and optionally balance
                db.upsertGlobalUserIfNewer(id, p.getName(), getEssentialsBalanceDouble(u), now);
                if (balanceWriteEnabled) {
                    db.updateBalanceIfNewer(id, getEssentialsBalanceDouble(u), now);
                }

                // Per-server profile
                String grp = safeGroup(u);
                String loc = LocationCodec.serialize(safeLastLocation(u, p));
                db.updateGroupIfNewer(id, serverName, grp, now);
                db.updateLastLocationIfNewer(id, serverName, loc, now);

                dirty.put(id, new DirtyBits());
            } catch (Exception ex) {
                logger.warning("[EssentialsXDataWorker] export profile failed for " + p.getName() + ": " + ex.getMessage());
            }
        });
    }

    private void applyProfileToPlayer(User u, DBCommands.ServerProfile sp) {
        if (u == null || sp == null) return;

        // Last location
        try {
            Location loc = LocationCodec.deserialize(sp.lastLocation);
            if (loc != null) {
                u.setLastLocation(loc);
            }
        } catch (Exception ignored) {}

        try { u.save(); } catch (Exception ignored) {}
    }

    // ─────────────────────────── Utils ───────────────────────────

    private static boolean isProfileEmpty(String group, String lastLoc) {
        boolean noGroup = group == null || group.isBlank();
        boolean noLoc = lastLoc == null || lastLoc.isBlank() || "NULL".equalsIgnoreCase(lastLoc);
        return noGroup && noLoc;
    }

    private static boolean profileEquals(String localGroup, String localLoc, String dbGroup, String dbLoc) {
        String lg = localGroup == null ? "" : localGroup;
        String dg = dbGroup == null ? "" : dbGroup;
        if (!lg.equals(dg)) return false;

        String ll = localLoc == null ? "" : localLoc;
        String dl = dbLoc == null ? "" : dbLoc;
        return ll.equals(dl);
    }

    private static String safeGroup(User u) {
        try {
            String g = u.getGroup();
            return g != null && !g.isBlank() ? g : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Location safeLastLocation(User u, Player p) {
        try {
            Location l = u.getLastLocation();
            if (l != null) return l;
        } catch (Exception ignored) {}
        try {
            Location l = u.getLocation();
            if (l != null) return l;
        } catch (Exception ignored) {}
        return p.getLocation();
    }

    private static double getEssentialsBalanceDouble(User u) {
        try {
            return u.getMoney().doubleValue();
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static void setEssentialsBalanceSafe(User u, BigDecimal amount) {
        try {
            if (amount == null) return;
            // Clamp negative
            if (amount.doubleValue() < 0) amount = BigDecimal.ZERO;
            u.setMoney(amount);
            u.save();
        } catch (Exception ignored) {}
    }

    // ─────────────────────────── Dirty flags ───────────────────────────

    private static final class DirtyBits {
        boolean lastLocation;
        boolean group;
        boolean balance;

        DirtyBits lastLocation(boolean v) { this.lastLocation = v; return this; }
        DirtyBits group(boolean v) { this.group = v; return this; }
        DirtyBits balance(boolean v) { this.balance = v; return this; }

        DirtyBits markAll() { this.lastLocation = this.group = this.balance = true; return this; }
        boolean isClean() { return !lastLocation && !group && !balance; }
    }
}
