
# EssentailsXMySQL
A Plugin to Sync EssentailsX Home and Money with the Database
=======
# EssentialsX MySQL Sync

Seamlessly synchronize your EssentialsX player data with a MySQL database in real-time, fully configurable and robust.

Webpage: https://secvers.org/plugins/essentials-mysql


V2 WIP:
New:
- Sync over multiple Server for Server-Networks
- Sync fully automatic
---

## Key Features

- **Real-time MySQL synchronization**  
  Ensures reliable and efficient data sync for all EssentialsX player data.

- **Customizable table mappings**  
  Map your EssentialsX data structure to fit your existing database schema.

- **Automatic reconnect and retry**  
  Built-in connection resilience for uninterrupted synchronization.

- **Concurrent data processing**  
  Optimized for minimal performance impact on your server.

- **Lightweight and optimized**  
  Designed to run on high-load servers without lag.

- **Detailed logging and metrics**  
  Full logs and performance metrics for easy debugging and monitoring.

---

## Configuration

```yaml
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
```
