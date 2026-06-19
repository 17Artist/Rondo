package priv.seventeen.artist.rondo.api

import org.bukkit.Bukkit
import priv.seventeen.artist.blink.BlinkLog
import priv.seventeen.artist.rondo.account.AccountManager
import priv.seventeen.artist.rondo.currency.Currency
import priv.seventeen.artist.rondo.currency.CurrencyRegistry
import priv.seventeen.artist.rondo.exchange.ExchangeManager
import priv.seventeen.artist.rondo.log.LogManager
import priv.seventeen.artist.rondo.log.TransactionLog
import priv.seventeen.artist.rondo.ranking.RankingEntry
import priv.seventeen.artist.rondo.ranking.RankingManager
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * Rondo 对外 API 门面
 */
object RondoAPI {

    // ===== 货币注册表 =====

    /** 获取货币 */
    fun getCurrency(id: String): Currency? = CurrencyRegistry.get(id)

    /** 获取所有货币 */
    fun getAllCurrencies(): List<Currency> = CurrencyRegistry.getAll().toList()

    /** 货币是否已注册 */
    fun isCurrencyRegistered(id: String): Boolean = CurrencyRegistry.isRegistered(id)

    // ===== 余额操作 =====

    /** 获取余额 */
    fun getBalance(player: UUID, currencyId: String): BigDecimal {
        return AccountManager.getBalance(player, currencyId)
    }

    /** 是否有足够余额 */
    fun hasBalance(player: UUID, currencyId: String, amount: BigDecimal): Boolean {
        return getBalance(player, currencyId) >= amount
    }

    /** 存入货币 */
    fun deposit(player: UUID, currencyId: String, amount: BigDecimal, source: String): Boolean {
        if (amount <= BigDecimal.ZERO) return false
        val currency = CurrencyRegistry.get(currencyId) ?: return false
        // 归一到货币精度，避免存储超出精度的小数导致后续显示异常
        val amt = amount.setScale(currency.decimalPlaces, RoundingMode.HALF_UP)
        if (amt <= BigDecimal.ZERO) return false

        val oldBalance = getBalance(player, currencyId)
        val newBalance = oldBalance.add(amt)

        // 触发事件
        val event = EconomyTransactionEvent(player, currency, EconomyTransactionEvent.Action.DEPOSIT, amt, oldBalance, newBalance, source)
        Bukkit.getPluginManager().callEvent(event)
        if (event.isCancelled) return false

        val success = AccountManager.depositOffline(player, currencyId, amt, source)
        if (success) {
            LogManager.submit(TransactionLog(
                playerUuid = player,
                currencyId = currencyId,
                action = TransactionLog.Action.DEPOSIT,
                amount = amt,
                balanceAfter = newBalance,
                source = source
            ))
        }
        return success
    }

    /** 扣除货币 */
    fun withdraw(player: UUID, currencyId: String, amount: BigDecimal, source: String): Boolean {
        if (amount <= BigDecimal.ZERO) return false
        val currency = CurrencyRegistry.get(currencyId) ?: return false
        val amt = amount.setScale(currency.decimalPlaces, RoundingMode.HALF_UP)
        if (amt <= BigDecimal.ZERO) return false

        val oldBalance = getBalance(player, currencyId)
        val newBalance = oldBalance.subtract(amt)

        // 触发事件
        val event = EconomyTransactionEvent(player, currency, EconomyTransactionEvent.Action.WITHDRAW, amt, oldBalance, newBalance, source)
        Bukkit.getPluginManager().callEvent(event)
        if (event.isCancelled) return false

        val success = AccountManager.withdrawOffline(player, currencyId, amt, source)
        if (success) {
            LogManager.submit(TransactionLog(
                playerUuid = player,
                currencyId = currencyId,
                action = TransactionLog.Action.WITHDRAW,
                amount = amt,
                balanceAfter = newBalance,
                source = source
            ))
        }
        return success
    }

    /** 设置余额 */
    fun setBalance(player: UUID, currencyId: String, amount: BigDecimal, source: String): Boolean {
        val currency = CurrencyRegistry.get(currencyId) ?: return false
        val amt = amount.setScale(currency.decimalPlaces, RoundingMode.HALF_UP)

        val oldBalance = getBalance(player, currencyId)

        // 触发事件
        val event = EconomyTransactionEvent(player, currency, EconomyTransactionEvent.Action.SET, amt, oldBalance, amt, source)
        Bukkit.getPluginManager().callEvent(event)
        if (event.isCancelled) return false

        val success = AccountManager.setBalanceOffline(player, currencyId, amt, source)
        if (success) {
            LogManager.submit(TransactionLog(
                playerUuid = player,
                currencyId = currencyId,
                action = TransactionLog.Action.SET,
                amount = amt,
                balanceAfter = amt,
                source = source
            ))
        }
        return success
    }

    /** 转账 */
    fun transfer(from: UUID, to: UUID, currencyId: String, amount: BigDecimal): TransferResult {
        if (amount <= BigDecimal.ZERO) return TransferResult(false, "无效金额")
        val currency = CurrencyRegistry.get(currencyId) ?: return TransferResult(false, "货币不存在")
        if (!currency.transferable) return TransferResult(false, "该货币不支持转账")
        if (from == to) return TransferResult(false, "不能给自己转账")

        // 归一到货币精度
        val amt = amount.setScale(currency.decimalPlaces, RoundingMode.HALF_UP)
        if (amt <= BigDecimal.ZERO) return TransferResult(false, "无效金额")

        // 计算税
        val taxRate = BigDecimal.valueOf(currency.transferTaxRate)
        val taxAmount = amt.multiply(taxRate).setScale(currency.decimalPlaces, RoundingMode.HALF_UP)
        val totalCost = amt.add(taxAmount)

        // 检查余额
        if (!hasBalance(from, currencyId, totalCost)) {
            return TransferResult(false, "余额不足", taxAmount = taxAmount)
        }

        // 触发事件
        val event = EconomyTransferEvent(from, to, currency, amt, taxAmount)
        Bukkit.getPluginManager().callEvent(event)
        if (event.isCancelled) return TransferResult(false, "转账被取消", taxAmount = taxAmount)

        // 执行转账
        val withdrawSuccess = AccountManager.withdrawOffline(from, currencyId, totalCost, "transfer:out")
        if (!withdrawSuccess) return TransferResult(false, "扣款失败", taxAmount = taxAmount)

        val depositSuccess = AccountManager.depositOffline(to, currencyId, amt, "transfer:in")
        if (!depositSuccess) {
            // 回滚扣款（强制存入，绕过余额上限，确保不丢失资金）
            val rollbackSuccess = AccountManager.forceDepositOffline(from, currencyId, totalCost, "transfer:rollback")
            if (!rollbackSuccess) {
                // 回滚失败，记录严重错误日志
                BlinkLog.warn("§c[CRITICAL] Transfer rollback failed! From=$from, To=$to, Currency=$currencyId, Amount=$totalCost")
                LogManager.submit(TransactionLog(
                    playerUuid = from, currencyId = currencyId,
                    action = TransactionLog.Action.DEPOSIT,
                    amount = totalCost, balanceAfter = getBalance(from, currencyId),
                    source = "transfer:rollback:FAILED"
                ))
            }
            return TransferResult(false, "存入失败", taxAmount = taxAmount)
        }

        // 记录日志
        val fromBalance = getBalance(from, currencyId)
        val toBalance = getBalance(to, currencyId)

        LogManager.submit(TransactionLog(
            playerUuid = from, currencyId = currencyId,
            action = TransactionLog.Action.TRANSFER_OUT,
            amount = totalCost, balanceAfter = fromBalance,
            source = "transfer:to:$to"
        ))
        LogManager.submit(TransactionLog(
            playerUuid = to, currencyId = currencyId,
            action = TransactionLog.Action.TRANSFER_IN,
            amount = amt, balanceAfter = toBalance,
            source = "transfer:from:$from"
        ))

        return TransferResult(true, "转账成功", taxAmount = taxAmount)
    }

    /** 兑换 */
    fun exchange(player: UUID, ruleId: String, targetAmount: BigDecimal): ExchangeResult {
        return ExchangeManager.execute(player, ruleId, targetAmount)
    }

    // ===== 排行榜 =====

    /** 获取排行榜 */
    fun getRanking(currencyId: String, page: Int, pageSize: Int = 10): List<RankingEntry> {
        return RankingManager.getRanking(currencyId, page, pageSize)
    }

    /** 获取玩家排名 */
    fun getPlayerRank(player: UUID, currencyId: String): Int? {
        return RankingManager.getPlayerRank(player, currencyId)
    }

    // ===== 流水查询 =====

    /** 查询流水日志 */
    fun getLog(player: UUID, currencyId: String?, page: Int, pageSize: Int = 10): List<TransactionLog> {
        return LogManager.query(player, currencyId, page, pageSize)
    }
}

/**
 * 转账结果
 */
data class TransferResult(
    val success: Boolean,
    val message: String,
    val taxAmount: BigDecimal = BigDecimal.ZERO
)

/**
 * 兑换结果
 */
data class ExchangeResult(
    val success: Boolean,
    val message: String,
    val fromAmount: BigDecimal = BigDecimal.ZERO,
    val toAmount: BigDecimal = BigDecimal.ZERO
)
