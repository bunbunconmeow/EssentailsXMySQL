package org.secverse.secVersEssentialsXMySQLConnector.worker;

import com.earth2me.essentials.Essentials;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.secverse.secVersEssentialsXMySQLConnector.helper.DBCommands;
import org.secverse.secVersEssentialsXMySQLConnector.helper.LocationCodec;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * PlayerDataWorker for MC 1.21.4+
 *
 * Responsibilities:
 * 1) Cross-server sync with Essentials player state using DBCommands.
 *    All player mutations on main thread. All DB I/O async.
 * 2) Dupe-Protection without breaking stacking:
 *    - Normal items: no ItemMeta writes. Duplicates are detected via content-hash key
 *      computed from material and meta with amount normalized.
 *    - Shulker boxes only: a persistent UID is embedded in ItemMeta to harden against
 *      classic shulker-duplication. Non-shulker items never receive UIDs.
 *
 * Safety:
 * - Per-player single-flight for flushes.
 * - Suppression window after DB->Player import to avoid bounce exports.
 * - Item pickup disabled during import to avoid race conditions.
 *
 * Config keys (config.yml):
 * dupeProtection:
 *   enabled: true
 *   action: "LOG"                 # LOG, DENY_MOVE, DELETE
 *   auditLog: true
 *   suppressMsAfterImport: 1500
 *   rescanSeconds: 60
 *   embedUidForShulkersOnly: true
 *   cleanupOldUidsOnStart: true
 */
public final class PlayerDataWorker implements Listener {

    // --------------------------------------------------------------------------------------------
    // Construction and configuration
    // --------------------------------------------------------------------------------------------

    private final Plugin plugin;
    private final Essentials essentials;
    private final DBCommands db;
    private final String serverName;
    private final Logger logger;

    private final int flushIntervalSeconds;
    private final boolean dupeEnabled;
    private final Action dupeAction;
    private final boolean dupeAuditLog;
    private final long suppressMsAfterImport;
    private final int dupeRescanSeconds;

    private final boolean onlyShulkerUID;
    private final boolean cleanupOldUidsOnStart;

    private BukkitRunnable flushTask;
    private BukkitRunnable dupeScanTask;

    public enum Action {
        LOG,
        DENY_MOVE,
        DELETE
    }

    /**
     * Constructs the worker with config-driven dupe protection.
     */
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

        var cfg = plugin.getConfig();
        this.dupeEnabled = cfg.getBoolean("dupeProtection.enabled", true);
        this.dupeAction = parseAction(cfg.getString("dupeProtection.action", "LOG"));
        this.dupeAuditLog = cfg.getBoolean("dupeProtection.auditLog", true);
        this.suppressMsAfterImport = cfg.getLong("dupeProtection.suppressMsAfterImport", 1500L);
        this.dupeRescanSeconds = Math.max(10, cfg.getInt("dupeProtection.rescanSeconds", 60));
        this.onlyShulkerUID = cfg.getBoolean("dupeProtection.embedUidForShulkersOnly", true);
        this.cleanupOldUidsOnStart = cfg.getBoolean("dupeProtection.cleanupOldUidsOnStart", true);
    }

    private static Action parseAction(String s) {
        if (s == null) return Action.LOG;
        try { return Action.valueOf(s.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { return Action.LOG; }
    }

    // --------------------------------------------------------------------------------------------
    // Sync state and guards
    // --------------------------------------------------------------------------------------------

    private final Map<UUID, DirtyBits> dirty = new ConcurrentHashMap<>();
    private final Set<UUID> flushing = ConcurrentHashMap.newKeySet();
    private final Map<UUID, SyncGuard> guards = new ConcurrentHashMap<>();

    private static final class DirtyBits {
        boolean xp;
        boolean vitals;
        boolean meta;
        long lastMarkedAt;

        DirtyBits xp(boolean v) { this.xp = v; touch(); return this; }
        DirtyBits vitals(boolean v) { this.vitals = v; touch(); return this; }
        DirtyBits meta(boolean v) { this.meta = v; touch(); return this; }
        DirtyBits markAll() { this.xp = this.vitals = this.meta = true; touch(); return this; }
        boolean isClean() { return !xp && !vitals && !meta; }
        void touch() { this.lastMarkedAt = System.currentTimeMillis(); }
        boolean isRecent(long ms) { return System.currentTimeMillis() - lastMarkedAt < ms; }
    }

    private static final class SyncGuard {
        enum Phase { IDLE, IMPORTING, APPLYING }
        volatile Phase phase = Phase.IDLE;
        volatile long lastAppliedAt = 0L;
        boolean suppressExportsNow(long windowMs) {
            return System.currentTimeMillis() - lastAppliedAt < windowMs;
        }
    }

    // --------------------------------------------------------------------------------------------
    // Dupe tracking
    // --------------------------------------------------------------------------------------------

    private final NamespacedKey UID_KEY = new NamespacedKey("secverse", "item_uid");
    private final Map<String, SeenUid> seenUids = new ConcurrentHashMap<>();

    private static final class SeenUid {
        volatile UUID lastPlayerHolder;
        volatile String lastContext;
        volatile long lastSeenMs;
        volatile int occurrences;
    }

    // --------------------------------------------------------------------------------------------
    // Lifecycle
    // --------------------------------------------------------------------------------------------

    /**
     * Registers listeners and starts periodic tasks.
     */
    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);

        if (dupeEnabled && cleanupOldUidsOnStart && onlyShulkerUID) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                stripUidsFromPlayerInventories(p);
            }
        }

        flushTask = new BukkitRunnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    flushIfDirtyAsync(p.getUniqueId());
                }
            }
        };
        flushTask.runTaskTimerAsynchronously(plugin, flushIntervalSeconds * 20L, flushIntervalSeconds * 20L);

        if (dupeEnabled && dupeRescanSeconds > 0) {
            dupeScanTask = new BukkitRunnable() {
                @Override public void run() {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        auditPlayerInventories(p, "periodic-scan");
                    }
                }
            };
            dupeScanTask.runTaskTimer(plugin, dupeRescanSeconds * 20L, dupeRescanSeconds * 20L);
        }
    }

    /**
     * Unregisters listeners and stops tasks.
     */
    public void stop() {
        HandlerList.unregisterAll(this);
        if (flushTask != null) try { flushTask.cancel(); } catch (Exception ignored) {}
        if (dupeScanTask != null) try { dupeScanTask.cancel(); } catch (Exception ignored) {}
        dirty.clear();
        flushing.clear();
        guards.clear();
        seenUids.clear();
    }

    // --------------------------------------------------------------------------------------------
    // Join / Quit
    // --------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        guards.putIfAbsent(id, new SyncGuard());
        dirty.put(id, new DirtyBits().markAll());

        if (dupeEnabled && cleanupOldUidsOnStart && onlyShulkerUID) {
            Bukkit.getScheduler().runTask(plugin, () -> stripUidsFromPlayerInventories(p));
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            long now = System.currentTimeMillis();
            try {
                db.ensureGlobalUser(id, p.getName(), now);
                db.ensureServerProfile(id, serverName, now);
                db.ensureUserState(id, serverName, now);

                DBCommands.UserState dbState = db.getUserState(id, serverName);
                LocalState local = snapshotLocal(p);

                boolean localEmpty = isLocalFresh(local);
                boolean dbUseful  = hasDbUsefulState(dbState);

                if (localEmpty && dbUseful) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        try { applyDbStateToPlayer(p, dbState); }
                        catch (Exception ex) { logger.warning("[PlayerDataWorker] apply DB->Player failed for " + p.getName() + ": " + ex.getMessage()); }
                    });
                    dirty.put(id, new DirtyBits());
                    var g = guards.get(id); if (g != null) g.lastAppliedAt = System.currentTimeMillis();
                } else if (!dbUseful && hasLocalUsefulState(local)) {
                    exportPlayerStateAsync(p, now);
                } else if (dbUseful && hasLocalUsefulState(local)) {
                    if (!dbEqualsLocal(dbState, local)) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            try { applyDbStateToPlayer(p, dbState); }
                            catch (Exception ex) { logger.warning("[PlayerDataWorker] reconcile DB->Player failed for " + p.getName() + ": " + ex.getMessage()); }
                        });
                        dirty.put(id, new DirtyBits());
                        var g = guards.get(id); if (g != null) g.lastAppliedAt = System.currentTimeMillis();
                    } else {
                        dirty.put(id, new DirtyBits());
                    }
                } else {
                    exportPlayerStateAsync(p, now);
                }
            } catch (Exception ex) {
                logger.warning("[PlayerDataWorker] onJoin sync decision failed for " + p.getName() + ": " + ex.getMessage());
            }
        });

        if (dupeEnabled) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> auditPlayerInventories(p, "post-join-scan"), 40L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> flushNow(p));
        dirty.remove(id);
        flushing.remove(id);
        guards.remove(id);
    }

    // --------------------------------------------------------------------------------------------
    // Dirty markers
    // --------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExpChange(PlayerExpChangeEvent e) { markXpDirty(e.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLevelChange(PlayerLevelChangeEvent e) { markXpDirty(e.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFoodChange(FoodLevelChangeEvent e) { if (e.getEntity() instanceof Player p) markVitalsDirty(p); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRegain(EntityRegainHealthEvent e) { if (e.getEntity() instanceof Player p) markVitalsDirty(p); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(org.bukkit.event.entity.EntityDamageEvent e) { if (e.getEntity() instanceof Player p) markVitalsDirty(p); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent e) { markMetaDirty(e.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRespawn(PlayerRespawnEvent e) { markMetaDirty(e.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent e) { markMetaDirty(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
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

    private void markXpDirty(Player p) { dirty.compute(p.getUniqueId(), (k, v) -> debounce((v == null ? new DirtyBits() : v).xp(true))); }
    private void markVitalsDirty(Player p) { dirty.compute(p.getUniqueId(), (k, v) -> debounce((v == null ? new DirtyBits() : v).vitals(true))); }
    private void markMetaDirty(Player p) { dirty.compute(p.getUniqueId(), (k, v) -> debounce((v == null ? new DirtyBits() : v).meta(true))); }
    private void markAllDirty(Player p) { dirty.put(p.getUniqueId(), new DirtyBits().markAll()); }
    private DirtyBits debounce(DirtyBits b) { return b.isRecent(150) ? b : b; }

    // --------------------------------------------------------------------------------------------
    // Periodic flush
    // --------------------------------------------------------------------------------------------

    private void flushIfDirtyAsync(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        if (p == null) return;
        DirtyBits bits = dirty.get(uuid);
        if (bits == null || bits.isClean()) return;
        if (!flushing.add(uuid)) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                SyncGuard g = guards.getOrDefault(uuid, new SyncGuard());
                if (g.suppressExportsNow(suppressMsAfterImport)) {
                    dirty.put(uuid, new DirtyBits());
                    return;
                }
                flushBits(p, bits);
            } catch (Exception ex) {
                logger.warning("[PlayerDataWorker] flush failed for " + p.getName() + ": " + ex.getMessage());
            } finally {
                flushing.remove(uuid);
            }
        });
    }

    private void flushNow(Player p) {
        UUID id = p.getUniqueId();
        if (!flushing.add(id)) return;
        try {
            DirtyBits bits = dirty.get(id);
            if (bits == null) bits = new DirtyBits().markAll();
            flushBits(p, bits);
        } catch (Exception ex) {
            logger.warning("[PlayerDataWorker] final flush failed for " + p.getName() + ": " + ex.getMessage());
        } finally {
            flushing.remove(id);
        }
    }

    private void flushBits(Player p, DirtyBits bits) throws Exception {
        long now = System.currentTimeMillis();
        UUID id = p.getUniqueId();

        if (bits.xp) {
            db.updateXpIfNewer(id, serverName, p.getLevel(), p.getTotalExperience(), p.getExp(), now);
        }
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
        if (bits.meta) {
            db.updateMetadataIfNewer(id, serverName,
                    toGameModeString(p.getGameMode()),
                    serializePotionEffectsJson(p),
                    null,
                    null,
                    LocationCodec.serialize(p.getBedSpawnLocation()),
                    now);
        }

        if (bits.xp || bits.vitals || bits.meta) {
            PlayerInventory inv = p.getInventory();
            byte[] main  = encodeStacks(inv.getStorageContents());
            byte[] off   = encodeStacks(new ItemStack[]{ inv.getItemInOffHand() });
            byte[] armor = encodeStacks(inv.getArmorContents());
            byte[] ender = encodeStacks(p.getEnderChest().getStorageContents());
            db.updateInventoryIfNewer(id, serverName, main, off, armor, ender, now);
        }

        dirty.put(id, new DirtyBits());
    }

    // --------------------------------------------------------------------------------------------
    // Import / Export helpers
    // --------------------------------------------------------------------------------------------

    /**
     * Exports the full player state asynchronously.
     */
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
                        health, getMaxHealthSafe(p),
                        p.getFoodLevel(), p.getSaturation(), p.getExhaustion(), now);

                db.updateMetadataIfNewer(id, serverName,
                        toGameModeString(p.getGameMode()),
                        serializePotionEffectsJson(p),
                        null, null,
                        LocationCodec.serialize(p.getBedSpawnLocation()),
                        now);

                dirty.put(id, new DirtyBits());
            } catch (Exception ex) {
                logger.warning("[PlayerDataWorker] export Player->DB failed for " + p.getName() + ": " + ex.getMessage());
            }
        });
    }

    /**
     * Applies DB snapshot to a player. Temporarily disables pickup to avoid mid-tick merges.
     * Starts suppression window to avoid immediate bounce export.
     */
    private void applyDbStateToPlayer(Player p, DBCommands.UserState s) {
        if (p == null || s == null) return;

        UUID id = p.getUniqueId();
        SyncGuard g = guards.computeIfAbsent(id, k -> new SyncGuard());
        g.phase = SyncGuard.Phase.IMPORTING;

        Bukkit.getScheduler().runTask(plugin, () -> {
            boolean prevPickup = p.getCanPickupItems();
            try {
                p.setCanPickupItems(false);

                PlayerInventory inv = p.getInventory();
                ItemStack[] main  = decodeStacks(s.invMain, inv.getStorageContents().length);
                ItemStack[] armor = decodeStacks(s.invArmor, inv.getArmorContents().length);
                ItemStack[] off   = decodeStacks(s.invOffhand, 1);
                ItemStack[] ender = decodeStacks(s.enderChest, p.getEnderChest().getStorageContents().length);

                inv.clear();
                p.getEnderChest().clear();

                if (dupeEnabled && onlyShulkerUID) {
                    tagUidsShulkerOnly(main);
                    tagUidsShulkerOnly(armor);
                    tagUidsShulkerOnly(off);
                    tagUidsShulkerOnly(ender);
                }

                inv.setStorageContents(main);
                inv.setArmorContents(armor);
                inv.setItemInOffHand(off.length > 0 ? off[0] : null);
                p.getEnderChest().setStorageContents(ender);

                p.setTotalExperience(0);
                p.setLevel(0);
                p.setExp(0);
                p.setLevel(Math.max(0, s.xpLevel));
                p.setExp(Math.max(0f, Math.min(1f, s.xpProgress)));

                double maxHealth = s.maxHealth > 0 ? s.maxHealth : getMaxHealthSafe(p);
                var attr = p.getAttribute(Attribute.MAX_HEALTH);
                if (attr != null) attr.setBaseValue(maxHealth);
                p.setHealth(clamp(s.health, 0, maxHealth));
                p.setFoodLevel(s.foodLevel);
                p.setSaturation(Math.max(0, s.saturation));
                p.setExhaustion(Math.max(0, s.exhaustion));

                if (s.gameMode != null) {
                    try { p.setGameMode(GameMode.valueOf(s.gameMode)); } catch (IllegalArgumentException ignored) {}
                }
                if (s.bedSpawnLoc != null) {
                    Location bed = LocationCodec.deserialize(s.bedSpawnLoc);
                    if (bed != null) p.setBedSpawnLocation(bed, true);
                }

                dirty.put(id, new DirtyBits());
                g.phase = SyncGuard.Phase.APPLYING;
                g.lastAppliedAt = System.currentTimeMillis();

                if (dupeEnabled) auditPlayerInventories(p, "post-import");

                Bukkit.getScheduler().runTaskLater(plugin, () -> p.setCanPickupItems(prevPickup), 10L);
            } finally {
                Bukkit.getScheduler().runTaskLater(plugin, () -> g.phase = SyncGuard.Phase.IDLE, 20L);
            }
        });
    }

    // --------------------------------------------------------------------------------------------
    // Dupe-Protection: shulker-only UIDs and hash-based audits
    // --------------------------------------------------------------------------------------------

    private boolean isShulkerBox(ItemStack it) {
        return it != null && !it.getType().isAir() && Tag.SHULKER_BOXES.isTagged(it.getType());
    }

    /**
     * Ensures a shulker box carries a persistent UID. Non-shulker stacks are never modified.
     * Returns true if the item meta was mutated.
     */
    private boolean ensureUidOnItem(ItemStack it) {
        if (!onlyShulkerUID) return false;
        if (!isShulkerBox(it)) return false;
        var meta = it.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String uid = pdc.get(UID_KEY, PersistentDataType.STRING);
        if (uid == null || uid.isBlank()) {
            pdc.set(UID_KEY, PersistentDataType.STRING, UUID.randomUUID().toString());
            it.setItemMeta(meta);
            return true;
        }
        return false;
    }

    private void tagUidsShulkerOnly(ItemStack[] arr) {
        if (!onlyShulkerUID || arr == null) return;
        for (ItemStack it : arr) {
            if (isShulkerBox(it)) ensureUidOnItem(it);
        }
    }

    private void stripUidsFromPlayerInventories(Player p) {
        stripNonShulkerUids(p.getInventory());
        stripNonShulkerUids(p.getEnderChest());
        p.updateInventory();
    }

    private void stripNonShulkerUids(Inventory inv) {
        if (inv == null) return;
        ItemStack[] contents = inv.getContents();
        if (contents == null) return;
        boolean changed = false;
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType().isAir()) continue;
            if (isShulkerBox(it)) continue;
            var meta = it.getItemMeta();
            if (meta == null) continue;
            var pdc = meta.getPersistentDataContainer();
            if (pdc.has(UID_KEY, PersistentDataType.STRING)) {
                pdc.remove(UID_KEY);
                it.setItemMeta(meta);
                changed = true;
            }
        }
        if (changed) inv.setContents(contents);
    }

    /**
     * Audits both inventories using hash keys that do not touch ItemMeta.
     * Shulker boxes may additionally carry UIDs but hashing still works for them as well.
     */
    private void auditPlayerInventories(Player p, String reason) {
        if (!dupeEnabled) return;

        Map<String, List<SlotRef>> keyToSlots = new HashMap<>();
        collectKeySlots(keyToSlots, p.getInventory());
        collectKeySlots(keyToSlots, p.getEnderChest());

        for (var e : keyToSlots.entrySet()) {
            String key = e.getKey();
            List<SlotRef> slots = e.getValue();
            if (slots.size() <= 1) continue;

            int maxStack = slots.get(0).stack.getMaxStackSize();
            int total = slots.stream().mapToInt(sr -> sr.stack.getAmount()).sum();

            if (dupeAuditLog) {
                logger.warning("[DupeGuard] Duplicate content key on " + p.getName()
                        + " slots=" + slotIndexes(slots) + " cause=" + reason);
            }

            switch (dupeAction) {
                case LOG:
                    break;
                case DENY_MOVE:
                case DELETE:
                    SlotRef keep = slots.get(0);
                    int toKeep = Math.min(maxStack, total);
                    keep.stack.setAmount(toKeep);
                    total -= toKeep;

                    for (int i = 1; i < slots.size(); i++) {
                        SlotRef s = slots.get(i);
                        s.inv.setItem(s.index, null);
                    }

                    if (dupeAction == Action.DENY_MOVE) {
                        while (total > 0) {
                            int put = Math.min(maxStack, total);
                            ItemStack extra = keep.stack.clone();
                            extra.setAmount(put);
                            // Do not write UIDs for non-shulkers
                            if (isShulkerBox(extra)) ensureUidOnItem(extra);
                            p.getInventory().addItem(extra);
                            total -= put;
                        }
                    } else {
                        if (total > 0 && dupeAuditLog) {
                            logger.warning("[DupeGuard] Deleted surplus items beyond one stack for " + p.getName());
                        }
                    }
                    p.updateInventory();
                    break;
            }
        }
    }

    private record SlotRef(Inventory inv, int index, ItemStack stack) {}

    private String slotIndexes(List<SlotRef> list) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (SlotRef s : list) {
            if (!first) sb.append(",");
            first = false;
            sb.append(s.inv.getType().name()).append(":").append(s.index);
        }
        return sb.toString();
    }

    private void collectKeySlots(Map<String, List<SlotRef>> map, Inventory inv) {
        if (inv == null) return;
        ItemStack[] contents = inv.getContents();
        if (contents == null) return;
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType().isAir()) continue;
            String key = computeStackKey(it);
            if (key == null) continue;
            map.computeIfAbsent(key, k -> new ArrayList<>()).add(new SlotRef(inv, i, it));
        }
    }

    /**
     * Computes a stable content key for a stack that ignores amount but includes
     * material and metadata. No ItemMeta writes are performed.
     */
    private String computeStackKey(ItemStack it) {
        try {
            ItemStack norm = it.clone();
            norm.setAmount(1);
            byte[] blob = serializeStack(norm);
            byte[] hash = sha256(blob);
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception ex) {
            return it.getType().name();
        }
    }

    private byte[] serializeStack(ItemStack it) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BukkitObjectOutputStream oos = new BukkitObjectOutputStream(baos)) {
            oos.writeObject(it);
            oos.flush();
            return baos.toByteArray();
        }
    }

    private byte[] sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(data);
        } catch (NoSuchAlgorithmException e) {
            return Arrays.copyOf(data, Math.min(32, data.length));
        }
    }

    // --------------------------------------------------------------------------------------------
    // Real-time dupe hooks
    // --------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!dupeEnabled) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        // Only shulkers receive UIDs
        ItemStack cursor = e.getCursor();
        ItemStack current = e.getCurrentItem();
        if (onlyShulkerUID) {
            if (cursor != null && isShulkerBox(cursor)) ensureUidOnItem(cursor);
            if (current != null && isShulkerBox(current)) ensureUidOnItem(current);
        }

        Bukkit.getScheduler().runTask(plugin, () -> auditPlayerInventories(p, "click"));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent e) {
        if (!dupeEnabled) return;

        ItemStack it = e.getItem();
        if (onlyShulkerUID && isShulkerBox(it)) ensureUidOnItem(it);

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (e.getSource().getHolder() instanceof Player ps) auditPlayerInventories((Player) e.getSource().getHolder(), "move-src");
            if (e.getDestination().getHolder() instanceof Player pd) auditPlayerInventories((Player) e.getDestination().getHolder(), "move-dst");
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        if (!dupeEnabled) return;
        ItemStack it = e.getEntity().getItemStack();
        if (onlyShulkerUID && isShulkerBox(it)) {
            if (ensureUidOnItem(it)) e.getEntity().setItemStack(it);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!dupeEnabled) return;
        if (!(e.getEntity() instanceof Player p)) return;

        ItemStack it = e.getItem().getItemStack();
        if (onlyShulkerUID && isShulkerBox(it)) ensureUidOnItem(it);

        Bukkit.getScheduler().runTask(plugin, () -> auditPlayerInventories(p, "pickup"));
    }

    // --------------------------------------------------------------------------------------------
    // Local snapshot & comparison
    // --------------------------------------------------------------------------------------------

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

    private boolean hasLocalUsefulState(LocalState s) { return !isLocalFresh(s); }

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

    // --------------------------------------------------------------------------------------------
    // Serialization helpers
    // --------------------------------------------------------------------------------------------

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

    // --------------------------------------------------------------------------------------------
    // Misc
    // --------------------------------------------------------------------------------------------

    private static String serializePotionEffectsJson(Player p) {
        if (p == null || p.getActivePotionEffects().isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder(256);
        sb.append('[');
        boolean first = true;
        for (var eff : p.getActivePotionEffects()) {
            if (!first) sb.append(',');
            first = false;
            String key = eff.getType().getKey().toString();
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
}