# ✨ EssentialsX MySQL Sync  

Seamlessly synchronize your **EssentialsX** player data with a **MySQL database** across single or multiple servers.  
Built for **modern Minecraft (1.21.4+)**, fully automatic, resilient, and configurable.  

🔗 Website: [secvers.org/plugins/essentials-mysql](https://secvers.org/plugins/essentials-mysql)  

---

## 🚀 What’s New (V2)

- ✅ **Multi-Server Network Sync**  
  Homes, balances, inventories, and more stay consistent across your Velocity/Bungee/Proxy setup.  

- ✅ **Smart Auto-Sync**  
  Detects whether DB or Player data should be trusted at join – no more manual imports/exports.  

- ✅ **Dedicated Workers**  
  - **PlayerDataWorker** → XP, health, hunger, inventories, potion effects, etc.  
  - **HomeDataWorker** → Homes synced instantly when players set, delete, or rename them.  
  - **EssentialsXDataWorker** → Balances, groups, and last-locations.  

- ✅ **Fail-Safe Syncing**  
  Only-if-newer DB guards prevent overwriting fresh data.  

- ✅ **Telemetry & Update Checker**  
  Opt-in telemetry with minimal data (plugin version, OS, server name) and auto-update notifications.  

---

## ✨ Key Features

- 🔄 **Real-time MySQL synchronization**  
  Inventories, stats, homes, balances, groups – synced automatically.  

- 🌐 **Multi-Server Awareness**  
  Each server has its own profile data (e.g. per-server homes) while global data like balance is shared.  

- 🛡️ **Only-if-newer Guards**  
  Prevents stale writes from overwriting fresh data in the database.  

- ⚡ **Performance Optimized**  
  Async DB I/O, dirty-flag-based flush system, minimal impact even under heavy load.  

- 📝 **Detailed Logging & Metrics**  
  Debug and monitor sync performance with ease.  

---

## ⚙️ Configuration

```yaml
# ──────────────────────────────
# Database
# ──────────────────────────────
mysql:
  host: "localhost"
  port: 3306
  database: "essentials"
  user: "minecraft"
  password: "yourPassword"
  enableSSL: false
  autoCommit: false

# ──────────────────────────────
# Server Identity
# ──────────────────────────────
serverName: "lobby-1"

# ──────────────────────────────
# Telemetry
# ──────────────────────────────
telemetry:
  enabled: true
  send_interval_seconds: 3600

# ──────────────────────────────
# Update Checker
# ──────────────────────────────
checkUpdate: true

# ──────────────────────────────
# Worker Settings
# ──────────────────────────────
playerdata:
  flush_interval_seconds: 20

homes:
  flush_interval_seconds: 20
  debounce_ticks: 10

essx:
  balance_write_enabled: true
  flush_interval_seconds: 20
