/*
 * Copyright 2026 17Artist
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package priv.seventeen.artist.rondo.api

import org.bukkit.Bukkit
import priv.seventeen.artist.rondo.account.AccountManager
import priv.seventeen.artist.rondo.currency.Currency
import priv.seventeen.artist.rondo.currency.CurrencyRegistry
import priv.seventeen.artist.rondo.currency.MoneyConstraints
import priv.seventeen.artist.rondo.exchange.ExchangeManager
import priv.seventeen.artist.rondo.log.LogManager
import priv.seventeen.artist.rondo.log.TransactionLog
import priv.seventeen.artist.rondo.ranking.RankingEntry
import priv.seventeen.artist.rondo.ranking.RankingManager
import priv.seventeen.artist.rondo.storage.AtomicBalanceFailure
import priv.seventeen.artist.rondo.storage.TransferBalanceRequest
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Rondo 对外 API 门面
 */
object RondoAPI {

    // ===== 货币注册表 =====

    /** 获取货币 */
    fun getCurrency(id: String): Currency? = CurrencyRegistry.get(id)

    /** 获取所有货币 */
    fun getAllCurrencies(): List<Currency> = CurrencyRegistry.getAll().toList()

    /** 获取所有已注册货币的规范小写 ID */
    fun getAllCurrencyIds(): Set<String> = CurrencyRegistry.getIds()

    /** 货币是否已注册 */
    fun isCurrencyRegistered(id: String): Boolean = CurrencyRegistry.isRegistered(id)

    // ===== 余额操作 =====

    /** 获取余额 */
    fun getBalance(player: UUID, currencyId: String): BigDecimal {
        return AccountManager.getBalance(player, currencyId)
    }

    /** 非阻塞探测玩家只读经济快照；未命中时返回 null，不访问数据库。 */
    fun peekEconomySnapshot(player: UUID): PlayerEconomySnapshot? {
        return AccountManager.peekEconomySnapshot(player)
    }

    /** 异步取得缓存或从存储加载玩家全部货币的只读快照。 */
    fun getEconomySnapshot(player: UUID): CompletableFuture<PlayerEconomySnapshot> {
        return AccountManager.loadEconomySnapshotAsync(player)
    }

    /** 是否有足够余额 */
    fun hasBalance(player: UUID, currencyId: String, amount: BigDecimal): Boolean {
        return amount >= BigDecimal.ZERO &&
            MoneyConstraints.isStorable(amount) &&
            getBalance(player, currencyId) >= amount
    }

    /** 存入货币 */
    fun deposit(player: UUID, currencyId: String, amount: BigDecimal, source: String): Boolean {
        if (amount <= BigDecimal.ZERO || !isValidSource(source)) return false
        val currency = CurrencyRegistry.get(currencyId) ?: return false
        // 归一到货币精度，避免存储超出精度的小数导致后续显示异常
        val amt = amount.setScale(currency.decimalPlaces, RoundingMode.HALF_UP)
        if (amt <= BigDecimal.ZERO || !MoneyConstraints.isStorable(amt)) return false

        return AccountManager.withPlayerLock(player) {
            val oldBalance = getBalance(player, currency.id)
            val newBalance = oldBalance.add(amt)
            if (!MoneyConstraints.isStorable(newBalance)) return@withPlayerLock false

            val event = EconomyTransactionEvent(
                player, currency, EconomyTransactionEvent.Action.DEPOSIT,
                amt, oldBalance, newBalance, source
            )
            Bukkit.getPluginManager().callEvent(event)
            if (event.isCancelled) return@withPlayerLock false

            val success = AccountManager.depositOffline(player, currency.id, amt, source)
            if (success) {
                LogManager.submit(TransactionLog(
                    playerUuid = player,
                    currencyId = currency.id,
                    action = TransactionLog.Action.DEPOSIT,
                    amount = amt,
                    balanceAfter = getBalance(player, currency.id),
                    source = source
                ))
            }
            success
        }
    }

    /** 扣除货币 */
    fun withdraw(player: UUID, currencyId: String, amount: BigDecimal, source: String): Boolean {
        if (amount <= BigDecimal.ZERO || !isValidSource(source)) return false
        val currency = CurrencyRegistry.get(currencyId) ?: return false
        val amt = amount.setScale(currency.decimalPlaces, RoundingMode.HALF_UP)
        if (amt <= BigDecimal.ZERO || !MoneyConstraints.isStorable(amt)) return false

        return AccountManager.withPlayerLock(player) {
            val oldBalance = getBalance(player, currency.id)
            val newBalance = oldBalance.subtract(amt)
            if (!MoneyConstraints.isStorable(newBalance)) return@withPlayerLock false

            val event = EconomyTransactionEvent(
                player, currency, EconomyTransactionEvent.Action.WITHDRAW,
                amt, oldBalance, newBalance, source
            )
            Bukkit.getPluginManager().callEvent(event)
            if (event.isCancelled) return@withPlayerLock false

            val success = AccountManager.withdrawOffline(player, currency.id, amt, source)
            if (success) {
                LogManager.submit(TransactionLog(
                    playerUuid = player,
                    currencyId = currency.id,
                    action = TransactionLog.Action.WITHDRAW,
                    amount = amt,
                    balanceAfter = getBalance(player, currency.id),
                    source = source
                ))
            }
            success
        }
    }

    /** 设置余额 */
    fun setBalance(player: UUID, currencyId: String, amount: BigDecimal, source: String): Boolean {
        if (!isValidSource(source)) return false
        val currency = CurrencyRegistry.get(currencyId) ?: return false
        val amt = amount.setScale(currency.decimalPlaces, RoundingMode.HALF_UP)
        if (!MoneyConstraints.isStorable(amt) ||
            (!currency.negativeAllowed && amt < BigDecimal.ZERO) ||
            (currency.hasMaxBalance && amt > currency.maxBalance)
        ) {
            return false
        }

        return AccountManager.withPlayerLock(player) {
            val oldBalance = getBalance(player, currency.id)
            val event = EconomyTransactionEvent(
                player, currency, EconomyTransactionEvent.Action.SET,
                amt, oldBalance, amt, source
            )
            Bukkit.getPluginManager().callEvent(event)
            if (event.isCancelled) return@withPlayerLock false

            val success = AccountManager.setBalanceOffline(player, currency.id, amt, source)
            if (success) {
                LogManager.submit(TransactionLog(
                    playerUuid = player,
                    currencyId = currency.id,
                    action = TransactionLog.Action.SET,
                    amount = amt,
                    balanceAfter = getBalance(player, currency.id),
                    source = source
                ))
            }
            success
        }
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
        val taxAmount = amt.multiply(currency.transferTaxRate)
            .setScale(currency.decimalPlaces, RoundingMode.HALF_UP)
        val totalCost = amt.add(taxAmount)

        if (!MoneyConstraints.isStorable(amt) || !MoneyConstraints.isStorable(totalCost)) {
            return TransferResult(false, "金额超出存储范围", taxAmount = taxAmount)
        }

        return AccountManager.withPlayerLocks(from, to) {
            if (!hasBalance(from, currency.id, totalCost)) {
                return@withPlayerLocks TransferResult(false, "余额不足", taxAmount = taxAmount)
            }

            val event = EconomyTransferEvent(from, to, currency, amt, taxAmount)
            Bukkit.getPluginManager().callEvent(event)
            if (event.isCancelled) {
                return@withPlayerLocks TransferResult(false, "转账被取消", taxAmount = taxAmount)
            }

            val atomic = AccountManager.transfer(
                TransferBalanceRequest(
                    from = from,
                    to = to,
                    currencyId = currency.id,
                    debitAmount = totalCost,
                    creditAmount = amt,
                    allowNegative = currency.negativeAllowed,
                    senderInitialBalance = currency.defaultBalance,
                    recipientInitialBalance = currency.defaultBalance,
                    recipientMaxBalance = currency.maxBalance.takeIf { currency.hasMaxBalance }
                        ?: MoneyConstraints.MAX_ABSOLUTE_BALANCE
                )
            )
            if (!atomic.success) {
                val message = when (atomic.failure) {
                    AtomicBalanceFailure.INSUFFICIENT_FUNDS -> "余额不足"
                    AtomicBalanceFailure.BALANCE_LIMIT -> "收款方余额将超过上限"
                    else -> "转账失败"
                }
                return@withPlayerLocks TransferResult(false, message, taxAmount = taxAmount)
            }

            LogManager.submit(TransactionLog(
                playerUuid = from,
                currencyId = currency.id,
                action = TransactionLog.Action.TRANSFER_OUT,
                amount = totalCost,
                balanceAfter = getBalance(from, currency.id),
                source = "transfer:to:$to"
            ))
            LogManager.submit(TransactionLog(
                playerUuid = to,
                currencyId = currency.id,
                action = TransactionLog.Action.TRANSFER_IN,
                amount = amt,
                balanceAfter = getBalance(to, currency.id),
                source = "transfer:from:$from"
            ))

            TransferResult(true, "转账成功", taxAmount = taxAmount)
        }
    }

    /** 兑换 */
    fun exchange(player: UUID, ruleId: String, targetAmount: BigDecimal): ExchangeResult {
        return ExchangeManager.execute(player, ruleId, targetAmount)
    }

    // ===== 排行榜 =====

    /** 获取排行榜 */
    fun getRanking(currencyId: String, page: Int, pageSize: Int = 10): List<RankingEntry> {
        if (page < 1 || pageSize !in 1..100) return emptyList()
        return RankingManager.getRanking(currencyId, page, pageSize)
    }

    /** 获取玩家排名 */
    fun getPlayerRank(player: UUID, currencyId: String): Int? {
        return RankingManager.getPlayerRank(player, currencyId)
    }

    // ===== 流水查询 =====

    /** 查询流水日志 */
    fun getLog(player: UUID, currencyId: String?, page: Int, pageSize: Int = 10): List<TransactionLog> {
        if (page < 1 || pageSize !in 1..100) return emptyList()
        return LogManager.query(player, currencyId, page, pageSize)
    }

    private fun isValidSource(source: String): Boolean {
        return source.isNotBlank() && source.length <= 128
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
