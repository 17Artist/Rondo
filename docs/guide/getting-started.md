# 快速开始

## 环境要求

| 项目 | 要求 |
|------|------|
| Minecraft | 1.18.2+ |
| 服务端 | Spigot / Paper |
| Java | 17+ |
| 可选 | Redis（跨服同步） |

## 安装

1. 将 `Rondo-1.0.0.jar` 放入 `plugins/` 目录
2. 重启服务器
3. 编辑 `plugins/Rondo/` 下的配置文件
4. 使用 `/rondo reload` 重载配置

## 首次启动

首次启动时，Rondo 会自动生成：

```
plugins/Rondo/
├── config.yml              # 主配置
├── messages.yml            # 消息配置
├── exchange.yml            # 兑换规则
└── currencies/             # 货币配置目录
    ├── gold.yml            # 金币（主货币）
    ├── points.yml          # 点券（充值货币）
    └── honor.yml           # 荣誉点（活动货币）
```

## 快速验证

```
/money bal                           # 查看自己的余额
/rondo give Steve gold 1000          # 给玩家发放金币
/money top gold                      # 查看金币排行榜
```

## 下一步

- [主配置详解](/guide/configuration) — 数据库、性能参数
- [货币配置](/guide/currencies) — 自定义你的货币体系
- [跨服同步](/guide/cross-server) — Redis 多服同步
- [命令参考](/guide/commands) — 所有可用命令
