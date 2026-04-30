# 安装部署

## 前置要求

### 必需
- Java 17 或更高版本
- Spigot 或 Paper 1.18.2+

### 可选
- [Vault](https://www.spigotmc.org/resources/vault.34315/) — 经济接口桥接
- [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) — 占位符支持
- Redis — 跨服同步

## 安装步骤

### 1. 安装 Rondo

1. 从 [Releases](https://github.com/17Artist/Rondo/releases) 下载 `Rondo-x.x.x.jar`
2. 放入 `plugins/` 目录
3. 启动服务器

### 2. 配置

首次启动后编辑配置文件，然后 `/rondo reload`。

## MySQL 部署

如果使用 MySQL 存储：

1. 创建数据库：
```sql
CREATE DATABASE rondo CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

2. 修改 `config.yml`：
```yaml
storage:
  type: mysql
  mysql:
    host: localhost
    port: 3306
    database: rondo
    username: your_user
    password: your_password
    pool-size: 10
```

3. 重启服务器或 `/rondo reload`

Rondo 会自动创建所需的表结构。

## 跨服部署 (Redis)

群组服（BungeeCord/Velocity）环境下启用跨服同步：

1. 确保所有子服安装相同版本的 Rondo
2. 所有子服 `storage.type` 设为 `mysql`，连接同一个数据库
3. 所有子服配置同一个 Redis：

```yaml
cross-server:
  enabled: true
  redis:
    host: your-redis-host
    port: 6379
    password: "your-password"
    database: 0
    pool-size: 8
  mysql-backup: true
  sync-channel: "rondo:sync"
```

4. 重启所有子服

::: tip 多组隔离
如果同一个 Redis 服务多组服务器，使用不同的 `database` 编号和 `sync-channel` 名称隔离。
:::

## 迁移

### 从 SQLite 迁移到 MySQL

目前需要手动导出/导入数据。后续版本将提供迁移命令。

### 从单服迁移到跨服

1. 先配置 MySQL，确保数据已在 MySQL 中
2. 启用 `cross-server.enabled: true`
3. 玩家首次上线时，数据会自动从 MySQL 同步到 Redis

## 故障排查

### 插件未加载
- 检查 Java 版本是否 >= 17
- 查看控制台错误日志

### 数据库连接失败
- 检查 MySQL 服务是否运行
- 检查用户名密码是否正确
- 检查数据库是否存在

### Redis 连接失败
- 检查 Redis 服务是否运行
- 检查密码是否正确
- 插件会自动回退到单服模式，不会崩溃
