# ✨ EssentialsX MySQL Sync  

[![License](https://img.shields.io/github/license/bunbunconmeow/EssentailsXMySQL?style=for-the-badge)](./LICENSE)  
[![Stars](https://img.shields.io/github/stars/bunbunconmeow/EssentailsXMySQL?style=for-the-badge)](https://github.com/bunbunconmeow/EssentailsXMySQL/stargazers)  
[![Issues](https://img.shields.io/github/issues/bunbunconmeow/EssentailsXMySQL?style=for-the-badge)](https://github.com/bunbunconmeow/EssentailsXMySQL/issues)  

Seamlessly synchronize your **EssentialsX** player data with a **MySQL database** across single or multiple servers.  
Supports **Minecraft 1.21.4+**, fully automatic, resilient, and configurable.  

🔗 Website: [secvers.org/plugins/essentials-mysql](https://secvers.org/plugins/essentials-mysql)  
🔗 GitHub: [bunbunconmeow/EssentailsXMySQL](https://github.com/bunbunconmeow/EssentailsXMySQL)  

---

## 🚀 What’s New (V2)

- 🌐 **Multi-Server Network Sync** – balances, homes, inventories, and profiles sync seamlessly across servers  
- ⚡ **Smart Auto-Sync** – decides at join whether DB or Player data should be trusted  
- 🛠️ **Dedicated Workers**:  
  - `PlayerDataWorker` → XP, health, hunger, inventories, potion effects  
  - `HomeDataWorker` → Homes synced instantly when set, deleted, or renamed  
  - `EssentialsXDataWorker` → Balances, groups, and last-locations  
- 🔒 **Only-if-newer Guards** – prevents overwriting fresh data with stale states  
- 📡 **Telemetry & Update Checker** – optional, minimal data (plugin version, OS, server name)  

---

## ✨ Features

✔ Real-time MySQL sync  
✔ Multi-server awareness  
✔ Async DB I/O, dirty-flagged flushing  
✔ Safe imports/exports at join  
✔ Debug-friendly logging & metrics  

---

## ⚙️ Configuration Example

```yaml
# EssentialsX MySQL Sync config.yml
# ──────────────────────────────
# Database Connection Settings
# ──────────────────────────────
mysql:
  # Database hostname or IP
  host: "localhost"

  # Port number of the database
  port: 3306

  # Name of the database schema
  database: "essentials"

  # MySQL or MariaDB user credentials
  user: "minecraft"
  password: "yourPassword"

  # SSL usage (true/false)
  enableSSL: false

  # Whether to use autocommit for statements
  autoCommit: false


# ──────────────────────────────
# Server Identification
# ──────────────────────────────
# This name is stored with per-server profile data
# and must be unique across your network.
serverName: "default"


# ──────────────────────────────
# Telemetry
# ──────────────────────────────
# We only collect minimal info (plugin version, OS, server name).
telemetry:
  enabled: true                  # set to false to opt-out
  send_interval_seconds: 3600    # how often telemetry pings are sent


# ──────────────────────────────
# Update Checker
# ──────────────────────────────
checkUpdate: true


# ──────────────────────────────
# Worker Settings
# ──────────────────────────────
playerdata: #experimantal
  enabled: false
  flush_interval_seconds: 20

homes:
  # How often homes are flushed to DB
  flush_interval_seconds: 20
  # Debounce delay in ticks after sethome/delhome/renamehome before writing
  debounce_ticks: 10

essx:
  # If true, this server writes global balances back to DB.
  # If false, balances are imported from DB but never exported.
  balance_write_enabled: true
  # How often EssentialsX profile data (balance, group, last location) is flushed
  flush_interval_seconds: 20
  
#experimantal do not enable only for testing
dupeProtection: 
  enabled: false
  action: "LOG"
  auditLog: true
  suppressMsAfterImport: 1500
  rescanSeconds: 60
  embedUidForShulkersOnly: true
  cleanupOldUidsOnStart: true

