package org.secverse.secVersEssentialsXMySQLConnector;

import com.earth2me.essentials.Essentials;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.secverse.secVersEssentialsXMySQLConnector.SecVersCom.Telemetry;
import org.secverse.secVersEssentialsXMySQLConnector.SecVersCom.UpdateChecker;
import org.secverse.secVersEssentialsXMySQLConnector.helper.DBCommands;
import org.secverse.secVersEssentialsXMySQLConnector.helper.database;
import org.secverse.secVersEssentialsXMySQLConnector.worker.EssentialsXDataWorker;
import org.secverse.secVersEssentialsXMySQLConnector.worker.HomeDataWorker;
import org.secverse.secVersEssentialsXMySQLConnector.worker.PlayerDataWorker;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Main plugin class that wires EssentialsX + MySQL sync workers.
 */
public final class SecVersEssentialsXMySQLConnector extends JavaPlugin {

    private Essentials essentials;
    private database dbHelper;
    private DBCommands db;

    // Workers
    private PlayerDataWorker playerDataWorker;
    private HomeDataWorker homeDataWorker;
    private EssentialsXDataWorker essentialsXDataWorker;

    // Optional services
    private UpdateChecker updateChecker;
    private Telemetry telemetry;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        final FileConfiguration cfg = getConfig();

        // EssentialsX presence
        essentials = (Essentials) Bukkit.getPluginManager().getPlugin("Essentials");
        if (essentials == null) {
            getLogger().severe("EssentialsX not found. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Database connection
        dbHelper = new database(cfg);
        try {
            dbHelper.connect();
            // Your database helper currently exposes setupTable(); keep this call name.
            dbHelper.setupTable();
            db = new DBCommands(dbHelper.getConnection());
        } catch (SQLException ex) {
            getLogger().log(Level.SEVERE, "SQL initialization error", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Update check
        if (cfg.getBoolean("checkUpdate", true)) {
            updateChecker = new UpdateChecker(this);
            updateChecker.checkNowAsync();
        }

        if (cfg.getBoolean("telemetry.enabled", true)) {
            telemetry = new Telemetry(this);
            Map<String, Object> boot = new HashMap<>();
            boot.put("event", "plugin_enable");
            telemetry.sendTelemetryAsync(boot);

            int interval = cfg.getInt("telemetry.send_interval_seconds", 3600);
            new BukkitRunnable() {
                @Override
                public void run() {
                    Map<String, Object> periodic = new HashMap<>();
                    periodic.put("event", "periodic_ping");
                    telemetry.sendTelemetryAsync(periodic);
                }
            }.runTaskTimerAsynchronously(this, interval * 20L, interval * 20L);
        }

        final String serverName = cfg.getString("serverName", getServer().getName());

        final int playerFlushSecs = cfg.getInt("playerdata.flush_interval_seconds", 20);
        final int homesFlushSecs = cfg.getInt("homes.flush_interval_seconds", 20);
        final int homesDebounceTicks = cfg.getInt("homes.debounce_ticks", 10);
        final int exFlushSecs = cfg.getInt("essx.flush_interval_seconds", 20);
        final boolean balanceWriteEnabled = cfg.getBoolean("essx.balance_write_enabled", true);

        // Start workers
        playerDataWorker = new PlayerDataWorker(
                this,
                essentials,
                db,
                serverName,
                playerFlushSecs
        );
        playerDataWorker.start();

        homeDataWorker = new HomeDataWorker(
                this,
                essentials,
                db,
                serverName,
                homesFlushSecs,
                homesDebounceTicks
        );
        homeDataWorker.start();

        essentialsXDataWorker = new EssentialsXDataWorker(
                this,
                essentials,
                db,
                serverName,
                balanceWriteEnabled,
                exFlushSecs
        );
        essentialsXDataWorker.start();

        getLogger().info("Essentials SQL Sync enabled");
    }

    @Override
    public void onDisable() {
        // Stop workers first
        safeStopWorkers();

        // Close DB last
        if (dbHelper != null) {
            dbHelper.close();
        }

        if (telemetry != null) {
            telemetry = null;
        }
    }

    /**
     * Stops all workers if they were started.
     */
    private void safeStopWorkers() {
        try { if (playerDataWorker != null) playerDataWorker.stop(); } catch (Exception ignored) {}
        try { if (homeDataWorker != null) homeDataWorker.stop(); } catch (Exception ignored) {}
        try { if (essentialsXDataWorker != null) essentialsXDataWorker.stop(); } catch (Exception ignored) {}
    }

    /**
     * Manual command entry point. Kept minimal on purpose since workers handle sync automatically.
     * /syncforce import|export
     */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;

        if (!p.hasPermission("essentials.sync")) {
            p.sendMessage("No permission.");
            return true;
        }

        if (!"syncforce".equalsIgnoreCase(cmd.getName())) {
            return false;
        }

        if (args.length == 0) {
            p.sendMessage("§7Usage: /syncforce <import|export>");
            return true;
        }

        final String mode = args[0];
        if ("export".equalsIgnoreCase(mode)) {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    p.sendMessage("§aSync is automatic. Periodic flush will export shortly.");
                } catch (Exception ex) {
                    getLogger().warning("Manual export failed for " + p.getName() + ": " + ex.getMessage());
                }
            });
            return true;
        } else if ("import".equalsIgnoreCase(mode)) {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    p.sendMessage("§aSync is automatic. Rejoin to trigger import decision, or wait for periodic reconciliation.");
                } catch (Exception ex) {
                    getLogger().warning("Manual import failed for " + p.getName() + ": " + ex.getMessage());
                }
            });
            return true;
        } else {
            p.sendMessage("§cUnknown mode. Use <import|export>");
            return true;
        }
    }
}
