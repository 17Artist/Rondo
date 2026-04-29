# 事件

Rondo 在关键操作前触发 Bukkit 事件，其他插件可以监听并取消这些操作。

## EconomyTransactionEvent

货币存取/设置操作前触发。

```kotlin
class EconomyTransactionEvent(
    val playerUuid: UUID,
    val currency: Currency,
    val action: Action,      // DEPOSIT, WITHDRAW, SET
    val amount: BigDecimal,
    val oldBalance: BigDecimal,
    val newBalance: BigDecimal,
    val source: String
) : Event(), Cancellable
```

### 监听示例

```kotlin
@EventHandler
fun onTransaction(event: EconomyTransactionEvent) {
    // 禁止单次存入超过 10000
    if (event.action == EconomyTransactionEvent.Action.DEPOSIT && event.amount > BigDecimal(10000)) {
        event.isCancelled = true
    }
}
```

## EconomyTransferEvent

转账操作前触发。

```kotlin
class EconomyTransferEvent(
    val from: UUID,
    val to: UUID,
    val currency: Currency,
    val amount: BigDecimal,
    val taxAmount: BigDecimal
) : Event(), Cancellable
```

### 监听示例

```kotlin
@EventHandler
fun onTransfer(event: EconomyTransferEvent) {
    // 记录大额转账
    if (event.amount > BigDecimal(50000)) {
        logger.warning("Large transfer: ${event.from} -> ${event.to}, amount: ${event.amount}")
    }
}
```

## EconomyExchangeEvent

货币兑换操作前触发。

```kotlin
class EconomyExchangeEvent(
    val playerUuid: UUID,
    val fromCurrency: Currency,
    val toCurrency: Currency,
    val fromAmount: BigDecimal,
    val toAmount: BigDecimal
) : Event(), Cancellable
```

### 监听示例

```kotlin
@EventHandler
fun onExchange(event: EconomyExchangeEvent) {
    // VIP 玩家兑换免手续费（如果有的话）
    // 或者在特定条件下禁止兑换
}
```

## 注意事项

- 所有事件在**主线程**触发
- 取消事件后，对应操作不会执行
- `source` 字段可用于区分操作来源，避免循环触发
