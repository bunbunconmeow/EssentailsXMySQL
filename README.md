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
mysql:
  host: "localhost"
  port: 3306
  database: "essentials"
  user: "minecraft"
  password: "yourPassword"
  enableSSL: false
  autoCommit: false

serverName: "lobby-1"

telemetry:
  enabled: true
  send_interval_seconds: 3600

checkUpdate: true

playerdata:
  flush_interval_seconds: 20

homes:
  flush_interval_seconds: 20
  debounce_ticks: 10

essx:
  balance_write_enabled: true
  flush_interval_seconds: 20
