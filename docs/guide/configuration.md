# 主配置

主配置文件位于 `plugins/Rondo/config.yml`。

## 完整配置

```yaml
# 数据库配置
storage:
  # 存储类型: sqlite / mysql
  type: sqlite
  # MySQL 配置（仅 type=mysql 时生效）
  mysql:
    host: localhost
    port: 3306
    database: rondo
    username: root
    password: ""
    # 连接池大小
    pool-size: 10

# 性能配置
performance:
  # 批量保存间隔 (tick, 20tick=1秒)
  save-interval: 100
  # 日志队列大小
  log-queue-size: 1000
  # 排行榜刷新间隔 (tick)
  ranking-refresh: 6000
  # 排行榜保留条数
  ranking-size: 100

# 功能开关
features:
  # 是否对接 Vault
  vault-hook: true
  # 是否对接 PlaceholderAPI
  papi-hook: true
  # 是否记录流水日志
  transaction-log: true
  # 日志保留天数 (-1=永久)
  log-retention-days: 30
```

## 存储选择

### SQLite（默认）

- 零配置，开箱即用
- 数据存储在 `plugins/Rondo/data.db`
- 适合中小型服务器（100人以下）

### MySQL

- 需要外部 MySQL 服务器
- 支持连接池，高并发性能更好
- 适合大型服务器或多服联动
- 推荐 MySQL 8.0+

## 性能调优

### save-interval

批量保存间隔。值越小数据越安全（崩溃丢失少），但 I/O 压力越大。

| 场景    | 推荐值       |
|-------|-----------|
| 小型服务器 | 200 (10秒) |
| 中型服务器 | 100 (5秒)  |
| 大型服务器 | 60 (3秒)   |

### ranking-refresh

排行榜刷新间隔。刷新需要查询数据库，间隔太短会增加数据库压力。

| 场景   | 推荐值          |
|------|--------------|
| 一般   | 6000 (5分钟)   |
| 高频展示 | 2400 (2分钟)   |
| 低频使用 | 12000 (10分钟) |
