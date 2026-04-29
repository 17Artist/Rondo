# 兑换系统

兑换系统允许玩家将一种货币按固定比率兑换为另一种货币。

## 配置文件

兑换规则配置在 `plugins/Rondo/exchange.yml`：

```yaml
exchanges:
  # 规则 ID（唯一标识）
  star_yuan_to_protocol_ticket:
    # 源货币
    from: star_yuan
    # 目标货币
    to: protocol_ticket
    # 兑换比率（源:目标 = rate:1）
    rate: 160
    # 最小兑换数量（目标货币）
    min-amount: 1
    # 周期内最大兑换数量（-1=无限）
    max-per-period: -1
    # 限购周期: NONE / DAILY / WEEKLY / MONTHLY
    period: NONE
    # 是否启用
    enabled: true
```

## 字段说明

### rate

兑换比率，表示获得 1 单位目标货币需要消耗多少源货币。

例如 `rate: 160` 表示 160 源货币 = 1 目标货币。

### max-per-period & period

限购机制。`max-per-period` 是周期内允许兑换的最大目标货币数量。

| period  | 说明       |
|---------|----------|
| NONE    | 无限制      |
| DAILY   | 每日重置（0点） |
| WEEKLY  | 每周重置（周一） |
| MONTHLY | 每月重置（1号） |

## 使用方式

玩家命令：
```
/money exchange <源货币> <目标货币> <目标数量>
```

示例：
```
/money exchange star_yuan protocol_ticket 5
# 消耗 800 星元，获得 5 张协议凭证
```

## API 调用

其他插件可通过 API 触发兑换：

```kotlin
val result = RondoAPI.exchange(playerUuid, "star_yuan_to_protocol_ticket", BigDecimal(5))
if (result.success) {
    // 兑换成功
}
```
