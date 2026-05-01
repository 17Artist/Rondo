# 兑换系统

兑换系统允许玩家将一种货币按固定比率兑换为另一种货币。

## 配置文件

兑换规则配置在 `plugins/Rondo/exchange.yml`：

```yaml
exchanges:
  # 规则 ID（唯一标识）
  points_to_gold:
    # 源货币
    from: points
    # 目标货币
    to: gold
    # 兑换比率（消耗 rate 个源货币 = 1 个目标货币）
    rate: 0.01
    # 最小兑换数量（目标货币）
    min-amount: 100
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

例如 `rate: 0.01` 表示 1 点券 = 100 金币（消耗 0.01 点券获得 1 金币）。

### max-per-period & period

限购机制。`max-per-period` 是周期内允许兑换的最大目标货币数量。

| period | 说明 |
|--------|------|
| NONE | 无限制 |
| DAILY | 每日重置（0点） |
| WEEKLY | 每周重置（周一） |
| MONTHLY | 每月重置（1号） |

## 使用方式

玩家命令：
```
/money exchange <源货币> <目标货币> <目标数量>
```

示例：
```
/money exchange honor gold 500
# 消耗 50 荣誉点，获得 500 金币
```

## API 调用

其他插件可通过 API 触发兑换：

```kotlin
val result = RondoAPI.exchange(playerUuid, "honor_to_gold", BigDecimal(500))
if (result.success) {
    // 兑换成功
}
```
