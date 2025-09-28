package org.secverse.secVersEssentialsXMySQLConnector.worker;

import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.User;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.secverse.secVersEssentialsXMySQLConnector.helper.DBCommands;
import org.secverse.secVersEssentialsXMySQLConnector.helper.LocationCodec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * PlayerDataWorker for MC 1.21.4+
 *
 * Join policy:
 *   - If local player is "fresh/empty" and DB has useful state -> import DB -> Player.
 *   - If DB is empty/null and Player has useful state           -> export Player -> DB.
 *   - If both have state but differ                             -> apply DB -> Player.
 *
 * Ongoing sync:
 *   - Marks dirty on XP/vitals/meta events and periodically flushes changes to DB.
 *   - Final flush on quit.
 *
 * Safety:
 *   - All DB writes are guarded by last_update in DBCommands (only-if-newer).
 *   - All Player mutations run on main thread. DB I/O runs async.
 */
public final class PlayerDataWorker implements Listener {

    private final Plugin plugin;
    private final Essentials essentials;
    private final DBCommands db;
    private final String serverName;
    private final Logger logger;

    private final int flushIntervalSeconds;

    private final Map<UUID, DirtyBits> dirty = new ConcurrentHashMap<>();
    private BukkitRunnable flushTask;

    public PlayerDataWorker(Plugin plugin,
                            Essentials essentials,
                            DBCommands db,
                            String serverName,
                            int flushIntervalSeconds) {
        this.plugin = plugin;
        this.essentials = essentials;
        this.db = db;
        this.serverName = serverName;
        this.flushIntervalSeconds = Math.max(5, flushIntervalSeconds);
        this.logger = plugin.getLogger();
    }

    /**
     * Starts periodic flushing and registers listeners.
     */
    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);

        flushTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    flushIfDirtyAsync(p.getUniqueId());
                }
            }
        };
        flushTask.runTaskTimerAsynchronously(plugin, flushIntervalSeconds * 20L, flushIntervalSeconds * 20L);
    }

    /**
     * Stops periodic flushing and unregisters listeners.
     */
    public void stop() {
        HandlerList.unregisterAll(this);
        if (flushTask != null) {
            try { flushTask.cancel(); } catch (Exception ignored) {}
        }
        dirty.clear();
    }

    // ─────────────────────────── Join / Quit ───────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        dirty.put(id, new DirtyBits().markAll()); // will be cleaned after initial decision

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            long now = System.currentTimeMillis();
            try {
                // Ensure rows exist
                db.ensureGlobalUser(id, p.getName(), now);
                db.ensureServerProfile(id, serverName, now);
                db.ensureUserState(id, serverName, now);

                // Load DB snapshot
                DBCommands.UserState dbState = db.getUserState(id, serverName);

                // Take a local snapshot for comparison
                LocalState local = snapshotLocal(p);

                boolean localEmpty = isLocalFresh(local);
                boolean dbUseful  = hasDbUsefulState(dbState);

                if (localEmpty && dbUseful) {
                    // DB -> Player
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        try {
                            applyDbStateToPlayer(p, dbState);
                        } catch (Exception ex) {
                            logger.warning("[PlayerDataWorker] apply DB->Player failed for " + p.getName() + ": " + ex.getMessage());
                        }
                    });
                    dirty.put(id, new DirtyBits()); // clean after import
                } else if (!dbUseful && hasLocalUsefulState(local)) {
                    // Player -> DB
                    exportPlayerStateAsync(p, now);
                } else if (dbUseful && hasLocalUsefulState(local)) {
                    // Both have data: if not equal, prefer DB -> Player
                    if (!dbEqualsLocal(dbState, local)) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            try {
                                applyDbStateToPlayer(p, dbState);
                            } catch (Exception ex) {
                                logger.warning("[PlayerDataWorker] reconcile DB->Player failed for " + p.getName() + ": " + ex.getMessage());
                            }
                        });
                        dirty.put(id, new DirtyBits()); // clean after import
                    } else {
                        // Equal, nothing to do
                        dirty.put(id, new DirtyBits());
                    }
                } else {
                    // Both empty or inconclusive, keep dirty to push minimal state soon
                    exportPlayerStateAsync(p, now);
                }
            } catch (Exception ex) {
                logger.warning("[PlayerDataWorker] onJoin sync decision failed for " + p.getName() + ": " + ex.getMessage());
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> flushNow(p));
        dirty.remove(p.getUniqueId());
    }

    // ─────────────────────────── Dirty markers ───────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExpChange(PlayerExpChangeEvent e) { markXpDirty(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLevelChange(PlayerLevelChangeEvent e) { markXpDirty(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFoodChange(FoodLevelChangeEvent e) { if (e.getEntity() instanceof Player p) markVitalsDirty(p); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRegain(EntityRegainHealthEvent e) { if (e.getEntity() instanceof Player p) markVitalsDirty(p); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) { if (e.getEntity() instanceof Player p) markVitalsDirty(p); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent e) { markMetaDirty(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRespawn(PlayerRespawnEvent e) { markMetaDirty(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent e) { markMetaDirty(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        // Record last death instantly
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                long now = System.currentTimeMillis();
                String deathStr = LocationCodec.serialize(p.getLocation());
                String bedStr = LocationCodec.serialize(p.getBedSpawnLocation());
                db.updateMetadataIfNewer(p.getUniqueId(), serverName,
                        toGameModeString(p.getGameMode()),
                        serializePotionEffectsJson(p),
                        null,
                        deathStr,
                        bedStr,
                        now);
            } catch (Exception ex) {
                logger.warning("[PlayerDataWorker] onDeath metadata update failed for " + p.getName() + ": " + ex.getMessage());
            }
        });
        markAllDirty(p);
    }

    private void markXpDirty(Player p) { dirty.compute(p.getUniqueId(), (k, v) -> (v == null ? new DirtyBits() : v).xp(true)); }
    private void markVitalsDirty(Player p) { dirty.compute(p.getUniqueId(), (k, v) -> (v == null ? new DirtyBits() : v).vitals(true)); }
    private void markMetaDirty(Player p) { dirty.compute(p.getUniqueId(), (k, v) -> (v == null ? new DirtyBits() : v).meta(true)); }
    private void markAllDirty(Player p) { dirty.put(p.getUniqueId(), new DirtyBits().markAll()); }

    // ─────────────────────────── Periodic flush ───────────────────────────

    private void flushIfDirtyAsync(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        if (p == null) return;
        DirtyBits bits = dirty.get(uuid);
        if (bits == null || bits.isClean()) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                flushBits(p, bits);
            } catch (Exception ex) {
                logger.warning("[PlayerDataWorker] flush failed for " + p.getName() + ": " + ex.getMessage());
            }
        });
    }

    private void flushNow(Player p) {
        DirtyBits bits = dirty.get(p.getUniqueId());
        if (bits == null) bits = new DirtyBits().markAll();
        try {
            flushBits(p, bits);
        } catch (Exception ex) {
            logger.warning("[PlayerDataWorker] final flush failed for " + p.getName() + ": " + ex.getMessage());
        }
    }

    private void flushBits(Player p, DirtyBits bits) throws Exception {
        long now = System.currentTimeMillis();
        UUID id = p.getUniqueId();

        // XP
        if (bits.xp) {
            db.updateXpIfNewer(id, serverName, p.getLevel(), p.getTotalExperience(), p.getExp(), now);
        }

        // Vitals
        if (bits.vitals) {
            double health = clamp(p.getHealth(), 0, getMaxHealthSafe(p));
            db.updateVitalsIfNewer(id, serverName,
                    health,
                    getMaxHealthSafe(p),
                    p.getFoodLevel(),
                    p.getSaturation(),
                    p.getExhaustion(),
                    now);
        }

        // Meta
        if (bits.meta) {
            String gm = toGameModeString(p.getGameMode());
            String potions = serializePotionEffectsJson(p);
            String statsJson = null;
            String lastDeath = null; // set on death instantly
            String bed = LocationCodec.serialize(p.getBedSpawnLocation());

            db.updateMetadataIfNewer(id, serverName, gm, potions, statsJson, lastDeath, bed, now);
        }

        // Inventory export when anything changed; adjust policy as needed
        if (bits.xp || bits.vitals || bits.meta) {
            PlayerInventory inv = p.getInventory();
            byte[] main  = encodeStacks(inv.getStorageContents());
            byte[] off   = encodeStacks(new ItemStack[]{ inv.getItemInOffHand() });
            byte[] armor = encodeStacks(inv.getArmorContents());
            byte[] ender = encodeStacks(p.getEnderChest().getStorageContents());

            db.updateInventoryIfNewer(id, serverName, main, off, armor, ender, now);
        }

        dirty.put(id, new DirtyBits()); // reset
    }

    // ─────────────────────────── Import / Export helpers ───────────────────────────

    private void exportPlayerStateAsync(Player p, long now) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                UUID id = p.getUniqueId();
                PlayerInventory inv = p.getInventory();

                byte[] main  = encodeStacks(inv.getStorageContents());
                byte[] off   = encodeStacks(new ItemStack[]{ inv.getItemInOffHand() });
                byte[] armor = encodeStacks(inv.getArmorContents());
                byte[] ender = encodeStacks(p.getEnderChest().getStorageContents());

                db.updateInventoryIfNewer(id, serverName, main, off, armor, ender, now);
                db.updateXpIfNewer(id, serverName, p.getLevel(), p.getTotalExperience(), p.getExp(), now);

                double health = clamp(p.getHealth(), 0, getMaxHealthSafe(p));
                db.updateVitalsIfNewer(id, serverName,
                        health,
                        getMaxHealthSafe(p),
                        p.getFoodLevel(),
                        p.getSaturation(),
                        p.getExhaustion(),
                        now);

                db.updateMetadataIfNewer(
                        id,
                        serverName,
                        toGameModeString(p.getGameMode()),
                        serializePotionEffectsJson(p),
                        null,
                        null,
                        LocationCodec.serialize(p.getBedSpawnLocation()),
                        now
                );

                dirty.put(id, new DirtyBits());
            } catch (Exception ex) {
                logger.warning("[PlayerDataWorker] export Player->DB failed for " + p.getName() + ": " + ex.getMessage());
            }
        });
    }

    private void applyDbStateToPlayer(Player p, DBCommands.UserState s) {
        if (p == null || s == null) return;

        // Inventory
        PlayerInventory inv = p.getInventory();
        ItemStack[] main  = decodeStacks(s.invMain, inv.getStorageContents().length);
        ItemStack[] armor = decodeStacks(s.invArmor, inv.getArmorContents().length);
        ItemStack[] off   = decodeStacks(s.invOffhand, 1);
        ItemStack[] ender = decodeStacks(s.enderChest, p.getEnderChest().getStorageContents().length);

        inv.setStorageContents(main);
        inv.setArmorContents(armor);
        inv.setItemInOffHand(off.length > 0 ? off[0] : null);
        p.getEnderChest().setStorageContents(ender);

        // XP
        p.setTotalExperience(0); // reset before applying to avoid double counting
        p.setLevel(0);
        p.setExp(0);
        p.setLevel(s.xpLevel);
        p.setExp(Math.max(0f, Math.min(1f, s.xpProgress)));

        // Vitals
        double maxHealth = s.maxHealth > 0 ? s.maxHealth : getMaxHealthSafe(p);
        var attr = p.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) attr.setBaseValue(maxHealth);
        p.setHealth(clamp(s.health, 0, maxHealth));
        p.setFoodLevel(s.foodLevel);
        p.setSaturation(Math.max(0, s.saturation));
        p.setExhaustion(Math.max(0, s.exhaustion));

        // Meta
        if (s.gameMode != null) {
            try { p.setGameMode(GameMode.valueOf(s.gameMode)); } catch (IllegalArgumentException ignored) {}
        }
        if (s.bedSpawnLoc != null) {
            Location bed = LocationCodec.deserialize(s.bedSpawnLoc);
            if (bed != null) p.setBedSpawnLocation(bed, true);
        }

        // Potion effects: left as JSON reference. If you want to apply them, parse and re-add.

        // Mark clean
        dirty.put(p.getUniqueId(), new DirtyBits());
    }

    // ─────────────────────────── Local snapshot & comparison ───────────────────────────

    private record LocalState(byte[] invMain, byte[] invOff, byte[] invArmor, byte[] ender,
                              int xpLevel, int xpTotal, float xpProgress,
                              double health, double maxHealth,
                              int food, float saturation, float exhaustion,
                              String gameMode, String bedSpawn) {}

    private LocalState snapshotLocal(Player p) {
        PlayerInventory inv = p.getInventory();
        return new LocalState(
                encodeStacks(inv.getStorageContents()),
                encodeStacks(new ItemStack[]{ inv.getItemInOffHand() }),
                encodeStacks(inv.getArmorContents()),
                encodeStacks(p.getEnderChest().getStorageContents()),
                p.getLevel(),
                p.getTotalExperience(),
                p.getExp(),
                clamp(p.getHealth(), 0, getMaxHealthSafe(p)),
                getMaxHealthSafe(p),
                p.getFoodLevel(),
                p.getSaturation(),
                p.getExhaustion(),
                toGameModeString(p.getGameMode()),
                LocationCodec.serialize(p.getBedSpawnLocation())
        );
    }

    private boolean isLocalFresh(LocalState s) {
        boolean invEmpty = isBlobEmpty(s.invMain) && isBlobEmpty(s.invOff) && isBlobEmpty(s.invArmor) && isBlobEmpty(s.ender);
        boolean xpEmpty  = s.xpLevel == 0 && s.xpTotal == 0 && s.xpProgress == 0f;
        boolean vitalsDefault = s.health <= 20.0 && s.maxHealth <= 20.0 && s.food == 20 && s.saturation >= 5.0f;
        return invEmpty && xpEmpty && vitalsDefault;
    }

    private boolean hasLocalUsefulState(LocalState s) {
        return !isLocalFresh(s);
    }

    private boolean hasDbUsefulState(DBCommands.UserState s) {
        if (s == null) return false;
        boolean inv = notEmpty(s.invMain) || notEmpty(s.invArmor) || notEmpty(s.invOffhand) || notEmpty(s.enderChest);
        boolean xp  = s.xpLevel > 0 || s.xpTotal > 0 || s.xpProgress > 0f;
        boolean vit = s.health > 0 || s.maxHealth > 20 || s.foodLevel != 20 || s.saturation != 5f;
        return inv || xp || vit;
    }

    private boolean dbEqualsLocal(DBCommands.UserState db, LocalState loc) {
        if (!Arrays.equals(safe(db.invMain),   safe(loc.invMain)))  return false;
        if (!Arrays.equals(safe(db.invArmor),  safe(loc.invArmor))) return false;
        if (!Arrays.equals(safe(db.invOffhand),safe(loc.invOff)))   return false;
        if (!Arrays.equals(safe(db.enderChest),safe(loc.ender)))    return false;

        if (db.xpLevel != loc.xpLevel) return false;
        if (db.xpTotal != loc.xpTotal) return false;
        if (Math.abs(db.xpProgress - loc.xpProgress) > 1e-4) return false;

        if (Math.abs(db.health - loc.health) > 1e-3) return false;
        if (Math.abs(db.maxHealth - loc.maxHealth) > 1e-3) return false;
        if (db.foodLevel != loc.food) return false;
        if (Math.abs(db.saturation - loc.saturation) > 1e-3) return false;
        if (Math.abs(db.exhaustion - loc.exhaustion) > 1e-3) return false;

        String gm = db.gameMode != null ? db.gameMode : "";
        String lgm = loc.gameMode != null ? loc.gameMode : "";
        if (!gm.equals(lgm)) return false;

        String bedDb = db.bedSpawnLoc != null ? db.bedSpawnLoc : "";
        String bedLo = loc.bedSpawn != null ? loc.bedSpawn : "";
        return bedDb.equals(bedLo);
    }

    // ─────────────────────────── Serialization helpers ───────────────────────────

    private static byte[] encodeStacks(ItemStack[] items) {
        if (items == null) return null;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BukkitObjectOutputStream oos = new BukkitObjectOutputStream(baos)) {
            oos.writeInt(items.length);
            for (ItemStack it : items) {
                oos.writeObject(it);
            }
            oos.flush();
            return baos.toByteArray();
        } catch (IOException ex) {
            return null;
        }
    }

    private static ItemStack[] decodeStacks(byte[] blob, int expectedLength) {
        if (blob == null || blob.length == 0) return new ItemStack[expectedLength];
        try (ByteArrayInputStream bais = new ByteArrayInputStream(blob);
             BukkitObjectInputStream ois = new BukkitObjectInputStream(bais)) {
            int len = ois.readInt();
            ItemStack[] arr = new ItemStack[len];
            for (int i = 0; i < len; i++) {
                try {
                    Object o = ois.readObject();
                    arr[i] = (o instanceof ItemStack) ? (ItemStack) o : null;
                } catch (ClassNotFoundException ignored) {
                    arr[i] = null;
                }
            }
            // Resize to expected length when necessary
            if (len == expectedLength) return arr;
            ItemStack[] out = new ItemStack[expectedLength];
            System.arraycopy(arr, 0, out, 0, Math.min(len, expectedLength));
            return out;
        } catch (IOException ex) {
            return new ItemStack[expectedLength];
        }
    }

    private static boolean isBlobEmpty(byte[] b) { return b == null || b.length == 0; }
    private static boolean notEmpty(byte[] b) { return b != null && b.length > 0; }
    private static byte[] safe(byte[] b) { return b == null ? new byte[0] : b; }

    // ─────────────────────────── Misc ───────────────────────────

    private static String serializePotionEffectsJson(Player p) {
        if (p == null || p.getActivePotionEffects().isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder(256);
        sb.append('[');
        boolean first = true;
        for (var eff : p.getActivePotionEffects()) {
            if (!first) sb.append(',');
            first = false;
            String key = eff.getType().getKey().toString(); // "minecraft:speed"
            sb.append('{')
                    .append("\"type\":\"").append(key).append("\",")
                    .append("\"duration\":").append(eff.getDuration()).append(',')
                    .append("\"amplifier\":").append(eff.getAmplifier()).append(',')
                    .append("\"ambient\":").append(eff.isAmbient()).append(',')
                    .append("\"particles\":").append(eff.hasParticles())
                    .append('}');
        }
        sb.append(']');
        return sb.toString();
    }

    private static String toGameModeString(GameMode gm) { return gm != null ? gm.name() : null; }

    private static double getMaxHealthSafe(Player p) {
        var attr = p.getAttribute(Attribute.MAX_HEALTH);
        return attr != null ? attr.getValue() : 20.0;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ─────────────────────────── Dirty flags ───────────────────────────

    private static final class DirtyBits {
        boolean xp;
        boolean vitals;
        boolean meta;

        DirtyBits xp(boolean v) { this.xp = v; return this; }
        DirtyBits vitals(boolean v) { this.vitals = v; return this; }
        DirtyBits meta(boolean v) { this.meta = v; return this; }

        DirtyBits markAll() { this.xp = this.vitals = this.meta = true; return this; }
        boolean isClean() { return !xp && !vitals && !meta; }
    }
}
