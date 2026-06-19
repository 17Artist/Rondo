# 跨服同步

Rondo 支持通过 Redis 实现多服务端之间的经济数据强一致性同步。

## 工作原理

```
单服模式（默认）:
  操作 → 内存缓存 → 定时批量写入 SQLite/MySQL

跨服模式:
  操作 → Redis Lua 原子事务 → Pub/Sub 通知其他服务端
                ↓
        异步备份到 MySQL（持久化）
```

跨服模式下，所有余额操作（存入、扣除、设置）都在 Redis 中通过 Lua 脚本原子完成。余额校验和修改在同一条命令中执行，不存在竞态条件。

任何一台服务端的操作会通过 Redis Pub/Sub 实时通知其他服务端刷新内存缓存。

## 配置

在 `config.yml` 中启用：

```yaml
cross-server:
  # 是否启用跨服同步
  enabled: true
  # Redis 配置
  redis:
    host: localhost
    port: 6379
    password: ""
    database: 0
    pool-size: 8
  # 是否异步备份到 MySQL（推荐开启）
  mysql-backup: true
  # Pub/Sub 通道名（多组服务器可用不同通道隔离）
  sync-channel: "rondo:sync"
```

::: warning 前置条件
跨服模式需要：
- 所有子服安装相同版本的 Rondo
- 所有子服连接同一个 Redis 实例
- **所有子服必须使用 MySQL 存储**（`storage.type: mysql`，连接同一个数据库）
- 流水日志、排行榜以及兑换的周期限购都依赖共享 MySQL 才能跨服一致；若子服各自使用 SQLite，这些数据只在本服有效
- 建议同时开启 `mysql-backup: true` 定期将 Redis 数据备份到 MySQL
:::

## 数据流

### 存入/扣除操作
1. 调用 `RondoAPI.deposit()` 或 `withdraw()`
2. 执行 Redis Lua 脚本（原子校验余额 + 修改 + 累计统计）
3. 脚本执行成功后，由插件向 Pub/Sub 通道发布变更通知
4. 本服在写入后立即刷新自身缓存，因此监听器会忽略自己发出的消息；其他服的监听器收到通知
5. 如果该玩家在其他服在线，则从 Redis 读取最新余额刷新其内存缓存

### 玩家上线
1. 优先从 Redis 加载余额数据
2. 如果 Redis 中没有数据（首次），从 MySQL 加载并同步到 Redis

### 玩家下线
1. 将 Redis 中的数据备份到 MySQL（如果 `mysql-backup: true`）

### 定时备份
- 每 5 分钟将所有在线玩家的 Redis 数据备份到 MySQL
- 确保 Redis 故障时数据不丢失

## Redis 数据结构

| Key | 类型 | 说明 |
|-----|------|------|
| `rondo:bal:{uuid}` | Hash | 余额，field 为货币 ID |
| `rondo:earned:{uuid}` | Hash | 累计获得 |
| `rondo:spent:{uuid}` | Hash | 累计消耗 |

## 与单服模式的区别

| 特性 | 单服模式 | 跨服模式 |
|------|---------|---------|
| 数据源 | 内存缓存 | Redis |
| 持久化 | SQLite/MySQL | Redis + MySQL 备份 |
| 余额校验 | 内存判断 | Redis Lua 原子判断 |
| 跨服一致性 | 不支持 | 强一致 |
| 延迟 | 零（内存） | ~1ms（Redis 网络） |
| 依赖 | 无 | Redis 服务器 |

## 故障处理

### Redis 连接失败
- 插件启动时如果 Redis 连接失败，会自动回退到单服模式
- 控制台会输出错误日志

### Redis 宕机
- 如果 `mysql-backup: true`，数据已定期备份到 MySQL
- 重启 Redis 后，玩家上线时会从 MySQL 恢复数据到 Redis

### 多组服务器隔离
- 使用不同的 `sync-channel` 和 Redis `database` 编号隔离不同服务器组
