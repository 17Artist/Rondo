# 命令参考

## 玩家命令

主命令: `/money`

| 命令                                  | 权限               | 说明       |
|-------------------------------------|------------------|----------|
| `/money`                            | `rondo.use`      | 查看所有货币余额 |
| `/money <货币ID>`                     | `rondo.use`      | 查看指定货币余额 |
| `/money pay <玩家> <货币ID> <数量>`       | `rondo.transfer` | 转账给其他玩家  |
| `/money exchange <源货币> <目标货币> <数量>` | `rondo.exchange` | 货币兑换     |
| `/money log [货币ID] [页码]`            | `rondo.log`      | 查看交易记录   |
| `/money top <货币ID> [页码]`            | `rondo.top`      | 查看排行榜    |

### 转账示例

```
/money pay Steve dark_coin 500
```

转账时会自动扣除税费。例如暗币税率 5%，转账 500 实际扣除 525。

### 兑换示例

```
/money exchange star_yuan protocol_ticket 3
```

按配置的兑换比率执行，消耗源货币获得目标货币。

## 管理员命令

主命令: `/rondo`

| 命令                             | 权限            | 说明       |
|--------------------------------|---------------|----------|
| `/rondo give <玩家> <货币ID> <数量>` | `rondo.admin` | 发放货币     |
| `/rondo take <玩家> <货币ID> <数量>` | `rondo.admin` | 扣除货币     |
| `/rondo set <玩家> <货币ID> <数量>`  | `rondo.admin` | 设置余额     |
| `/rondo check <玩家> [货币ID]`     | `rondo.admin` | 查看玩家余额   |
| `/rondo log <玩家> [货币ID] [页码]`  | `rondo.admin` | 查看玩家流水   |
| `/rondo reload`                | `rondo.admin` | 重载所有配置   |
| `/rondo reset <玩家> <货币ID>`     | `rondo.admin` | 重置余额为默认值 |

### 离线操作

所有管理员命令均支持对离线玩家操作：

```
/rondo give OfflinePlayer star_yuan 1000
```

::: tip
玩家名支持在线和离线玩家。对离线玩家操作时直接写入数据库，玩家上线后自动加载最新数据。
:::
