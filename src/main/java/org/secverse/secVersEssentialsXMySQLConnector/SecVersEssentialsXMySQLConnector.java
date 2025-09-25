package org.secverse.secVersEssentialsXMySQLConnector;

import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.EssentialsEntityListener;
import com.earth2me.essentials.User;
import net.ess3.api.IUser;
import net.ess3.api.MaxMoneyException;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.secverse.secVersEssentialsXMySQLConnector.SecVersCom.Telemetry;
import org.secverse.secVersEssentialsXMySQLConnector.SecVersCom.UpdateChecker;
import org.secverse.secVersEssentialsXMySQLConnector.helper.DBCommands;
import org.secverse.secVersEssentialsXMySQLConnector.helper.database;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;





public final class SecVersEssentialsXMySQLConnector extends JavaPlugin implements Listener {

    private Essentials essentials;
    private database dbHelper;
    private DBCommands db;

    private UpdateChecker updateChecker;
    private Telemetry telemetry;


    // ───────────────────────── Utility: Location <-> String ─────────────────────────
    private static final String NULL_LOC = "NULL";
    private static String serializeLocation(Location loc) {
        if (loc == null) return NULL_LOC;
        return String.join(",",
                loc.getWorld().getName(),
                String.valueOf(loc.getX()),
                String.valueOf(loc.getY()),
                String.valueOf(loc.getZ()),
                String.valueOf(loc.getYaw()),
                String.valueOf(loc.getPitch())
        );
    }

    private static Location deserializeLocation(String str) {
        if (str == null || str.equalsIgnoreCase(NULL_LOC)) return null;
        String[] p = str.split(",");
        if (p.length < 6) return null;
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        return new Location(w,
                Double.parseDouble(p[1]),
                Double.parseDouble(p[2]),
                Double.parseDouble(p[3]),
                Float.parseFloat(p[4]),
                Float.parseFloat(p[5]));
    }

    private static String serializeHomes(Map<String, Location> homes) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Location> e : homes.entrySet()) {
            sb.append(e.getKey()).append('|').append(serializeLocation(e.getValue())).append(';');
        }
        return sb.toString();
    }

    private static Map<String, Location> deserializeHomes(String str) {
        Map<String, Location> map = new HashMap<>();
        if (str == null || str.isBlank()) return map;
        String[] entries = str.split(";");
        for (String entry : entries) {
            if (entry.isBlank()) continue;
            String[] parts = entry.split("\\|");
            if (parts.length != 2) continue;
            Location loc = deserializeLocation(parts[1]);
            if (loc != null) map.put(parts[0], loc);
        }
        return map;
    }

    // ───────────────────────────────────────────────────────────────────────────────
    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration cfg = getConfig();
        essentials = (Essentials) Bukkit.getPluginManager().getPlugin("Essentials");
        if (essentials == null) {
            getLogger().severe("EssentialsX not found...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        dbHelper = new database(cfg);
        try {
            dbHelper.connect();
            dbHelper.setupTable();
            db = new DBCommands(dbHelper.getConnection());
        } catch (SQLException ex) {
            getLogger().log(Level.SEVERE, "SQL initialization error", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Check for Update
        UpdateChecker checker = new UpdateChecker(this);
        boolean checkUpdate = getConfig().getBoolean("checkUpdate", true);

        if(checkUpdate) {
            checker.checkNowAsync();
        }


        // Telemetry
        telemetry = new Telemetry(this);
        Map<String, Object> add = new HashMap<>();
        add.put("event", "plugin_enable");
        telemetry.sendTelemetryAsync(add);

        int interval = getConfig().getInt("telemetry.send_interval_seconds", 3600);
        if (getConfig().getBoolean("telemetry.enabled", true)) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    Map<String, Object> periodic = new HashMap<>();
                    periodic.put("event", "periodic_ping");
                    telemetry.sendTelemetryAsync(periodic);
                }
            }.runTaskTimerAsynchronously(this, interval * 20L, interval * 20L); // seconds -> ticks
        }


        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("§aEssentials SQL Sync enabled");
    }

    @Override
    public void onDisable() {
        if (dbHelper != null) dbHelper.close();

        if(telemetry != null) {
            Map<String, Object> add = new HashMap<>();
            add.put("event", "plugin_disable");
            telemetry.sendTelemetryAsync(add);
        }


    }

    private void syncFromDB(Player player) {
        try {
            UUID uuid = player.getUniqueId();
            User user = essentials.getUser(player);
            ResultSet rs = db.getUser(uuid);

            if (!rs.next()) return;

            importFromDB(rs, user);
            user.save();
        } catch (Exception ex) {
            getLogger().log(Level.WARNING, "[IMPORT] Error for " + player.getName(), ex);
        }
    }

    private void syncToDB(Player player) {
        try {
            UUID uuid = player.getUniqueId();
            User user = essentials.getUser(player);
            String Groups = user.getGroup();


            String serializedLoc = serializeLocation(user.getLocation());

            // Fuck EssentailsX and Homes... JESUS was this a Pain to get this fuck running...
            Map<String, Location> homes = new HashMap<>();
            for (String home : user.getHomes()) {
                try {
                    homes.put(home, user.getHome(home));
                } catch (Exception e) {
                    getLogger().warning("Failed to get home '" + home + "': " + e.getMessage());
                }
            }
            String serializedHomes = serializeHomes(homes);
            db.upsertUser(uuid,
                    player.getName(),
                    user.getMoney().doubleValue(),
                    serializedLoc,
                    serializedHomes,
                    Groups,
                    System.currentTimeMillis() / 1000);

        } catch (Exception ex) {
            getLogger().log(Level.WARNING, "[EXPORT] Error for " + player.getName(), ex);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> syncToDB(player));
    }

    private void importFromDB(ResultSet rs, User user) throws SQLException, MaxMoneyException {
        double bal = rs.getDouble("balance");
        user.setMoney(new BigDecimal(bal));

        // Last location
        Location loc = deserializeLocation(rs.getString("last_location"));
        if (loc != null) user.setLastLocation(loc);

        // Homes
        String homesStr = rs.getString("homes");
        Map<String, Location> homes = deserializeHomes(homesStr);
        homes.forEach(user::setHome);
    }


    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;
        List<String> essentialsCommandsToSync = Arrays.asList("sethome", "delhome", "renamehome");
        String rawCommand = label.toLowerCase();
        if (essentialsCommandsToSync.contains(rawCommand)) {
            Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> syncToDB(p), 20L);
            return false;
        }
        if (!p.hasPermission("essentials.sync")) {
            p.sendMessage("No permission.");
            return true;
        }

        if (args.length == 0) {
            p.sendMessage("§7Usage: /syncforce <import|export>");
            return true;
        }

        String mode = args[0];
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            if (mode.equalsIgnoreCase("import")) {
                syncFromDB(p);
                p.sendMessage("§aImported Essentials data from the DB.");
            } else if (mode.equalsIgnoreCase("export")) {
                syncToDB(p);
                p.sendMessage("§aExported Essentials data to the DB.");
            } else {
                p.sendMessage("§cUnknown mode. Use <import|export>");
            }
        });

        return true;
    }
}
