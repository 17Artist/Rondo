# RondoAPI

`priv.seventeen.artist.rondo.api.RondoAPI` 是 Rondo 的核心 API 门面。

## 货币注册表

### getCurrency

获取货币定义。

```kotlin
fun getCurrency(id: String): Currency?
```

### getAllCurrencies

获取所有已注册货币。

```kotlin
fun getAllCurrencies(): List<Currency>
```

### isCurrencyRegistered

检查货币是否已注册。

```kotlin
fun isCurrencyRegistered(id: String): Boolean
```

## 余额操作

### getBalance

获取玩家余额。支持在线和离线玩家。

```kotlin
fun getBalance(player: UUID, currencyId: String): BigDecimal
```

### hasBalance

检查玩家是否有足够余额。

```kotlin
fun hasBalance(player: UUID, currencyId: String, amount: BigDecimal): Boolean
```

### deposit

存入货币。操作前触发 `EconomyTransactionEvent`。

```kotlin
fun deposit(player: UUID, currencyId: String, amount: BigDecimal, source: String): Boolean
```

**参数:**
- `player` — 玩家 UUID
- `currencyId` — 货币 ID
- `amount` — 金额（必须 > 0）
- `source` — 来源标识

**返回:** 是否成功

### withdraw

扣除货币。余额不足时返回 `false`。

```kotlin
fun withdraw(player: UUID, currencyId: String, amount: BigDecimal, source: String): Boolean
```

### setBalance

直接设置余额。

```kotlin
fun setBalance(player: UUID, currencyId: String, amount: BigDecimal, source: String): Boolean
```

## 转账

### transfer

玩家间转账，自动计算并扣除税费。

```kotlin
fun transfer(from: UUID, to: UUID, currencyId: String, amount: BigDecimal): TransferResult
```

**返回:**
```kotlin
data class TransferResult(
    val success: Boolean,
    val message: String,
    val taxAmount: BigDecimal = BigDecimal.ZERO
)
```

**失败原因:**
- 货币不存在
- 货币不支持转账
- 余额不足（含税）
- 事件被取消

## 兑换

### exchange

执行货币兑换。

```kotlin
fun exchange(player: UUID, ruleId: String, targetAmount: BigDecimal): ExchangeResult
```

**返回:**
```kotlin
data class ExchangeResult(
    val success: Boolean,
    val message: String,
    val fromAmount: BigDecimal = BigDecimal.ZERO,
    val toAmount: BigDecimal = BigDecimal.ZERO
)
```

## 排行榜

### getRanking

获取排行榜数据。

```kotlin
fun getRanking(currencyId: String, page: Int, pageSize: Int = 10): List<RankingEntry>
```

### getPlayerRank

获取玩家排名。

```kotlin
fun getPlayerRank(player: UUID, currencyId: String): Int?
```

## 流水日志

### getLog

查询交易流水。

```kotlin
fun getLog(player: UUID, currencyId: String?, page: Int, pageSize: Int = 10): List<TransactionLog>
```

`currencyId` 传 `null` 查询所有货币的流水。
