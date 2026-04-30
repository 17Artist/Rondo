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
    ├── dark_coin.yml       # 暗币
    ├── lithium.yml         # 锂核
    ├── star_yuan.yml       # 星元
    ├── protocol_ticket.yml # 协议凭证
    ├── star_dust.yml       # 星尘
    └── star_glory.yml      # 星辉
```

## 快速验证

```
/money bal                # 查看自己的余额
/rondo give Steve dark_coin 1000  # 给玩家发放货币
/money top dark_coin      # 查看排行榜
```

## 下一步

- [主配置详解](/guide/configuration) — 数据库、性能参数
- [货币配置](/guide/currencies) — 自定义你的货币体系
- [跨服同步](/guide/cross-server) — Redis 多服同步
- [命令参考](/guide/commands) — 所有可用命令
