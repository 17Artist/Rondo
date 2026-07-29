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

package priv.seventeen.artist.rondo.account

import priv.seventeen.artist.rondo.currency.CurrencyRegistry
import priv.seventeen.artist.rondo.currency.MoneyConstraints
import priv.seventeen.artist.rondo.storage.BalanceData
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 玩家账户 — 持有所有货币余额
 */
class Account(val playerUuid: UUID) {

    private val balances = ConcurrentHashMap<String, BalanceData>()

    @Volatile
    var dirty = false
        private set

    /** 加载余额数据 */
    @Synchronized
    fun load(data: Map<String, BalanceData>) {
        balances.clear()
        // 统一使用 lowercase key
        for ((key, value) in data) {
            balances[key.lowercase()] = value
        }
        // 为未初始化的货币设置默认值
        for (currency in CurrencyRegistry.getAll()) {
            if (!balances.containsKey(currency.id.lowercase())) {
                balances[currency.id.lowercase()] = BalanceData(
                    balance = currency.defaultBalance,
                    totalEarned = BigDecimal.ZERO,
                    totalSpent = BigDecimal.ZERO
                )
            }
        }
        dirty = false
    }

    /** 获取余额 */
    @Synchronized
    fun getBalance(currencyId: String): BigDecimal {
        return balances[currencyId.lowercase()]?.balance ?: CurrencyRegistry.get(currencyId)?.defaultBalance ?: BigDecimal.ZERO
    }

    /** 获取余额数据 */
    @Synchronized
    fun getBalanceData(currencyId: String): BalanceData {
        return balances[currencyId.lowercase()] ?: BalanceData(
            balance = CurrencyRegistry.get(currencyId)?.defaultBalance ?: BigDecimal.ZERO
        )
    }

    /** 获取所有余额 */
    @Synchronized
    fun getAllBalances(): Map<String, BalanceData> = balances.toMap()

    /** 是否有足够余额 */
    fun hasBalance(currencyId: String, amount: BigDecimal): Boolean {
        return getBalance(currencyId) >= amount
    }

    /** 存入 */
    @Synchronized
    fun deposit(currencyId: String, amount: BigDecimal): Boolean {
        if (amount <= BigDecimal.ZERO) return false
        val currency = CurrencyRegistry.get(currencyId) ?: return false
        val key = currencyId.lowercase()
        val current = getBalanceData(currencyId)
        val newBalance = current.balance.add(amount)

        // 检查上限
        if (!MoneyConstraints.isStorable(newBalance) ||
            (currency.hasMaxBalance && newBalance > currency.maxBalance)
        ) {
            return false
        }

        balances[key] = BalanceData(
            balance = newBalance,
            totalEarned = current.totalEarned.add(amount),
            totalSpent = current.totalSpent
        )
        dirty = true
        return true
    }

    /** 扣除 */
    @Synchronized
    fun withdraw(currencyId: String, amount: BigDecimal): Boolean {
        if (amount <= BigDecimal.ZERO) return false
        val currency = CurrencyRegistry.get(currencyId) ?: return false
        val key = currencyId.lowercase()
        val current = getBalanceData(currencyId)
        val newBalance = current.balance.subtract(amount)

        // 检查是否允许负数
        if (!MoneyConstraints.isStorable(newBalance) ||
            (!currency.negativeAllowed && newBalance < BigDecimal.ZERO)
        ) {
            return false
        }

        balances[key] = BalanceData(
            balance = newBalance,
            totalEarned = current.totalEarned,
            totalSpent = current.totalSpent.add(amount)
        )
        dirty = true
        return true
    }

    /** 设置余额 */
    @Synchronized
    fun setBalance(currencyId: String, amount: BigDecimal): Boolean {
        val currency = CurrencyRegistry.get(currencyId) ?: return false
        if (!MoneyConstraints.isStorable(amount)) return false
        if (!currency.negativeAllowed && amount < BigDecimal.ZERO) return false
        if (currency.hasMaxBalance && amount > currency.maxBalance) return false
        val key = currencyId.lowercase()
        val current = getBalanceData(currencyId)
        balances[key] = BalanceData(
            balance = amount,
            totalEarned = current.totalEarned,
            totalSpent = current.totalSpent
        )
        dirty = true
        return true
    }

    /** 标记为已保存 */
    @Synchronized
    fun markClean() {
        dirty = false
    }

    /** 标记为脏（保存失败时回滚用） */
    @Synchronized
    fun markDirty() {
        dirty = true
    }

    /** 原子快照并标记为 clean，避免竞态丢失脏标记 */
    @Synchronized
    fun snapshotAndClean(): Map<String, BalanceData> {
        if (!dirty) return emptyMap()
        val snapshot = balances.toMap()
        dirty = false
        return snapshot
    }

    /** 合并默认数据（加载期间已有内存操作时使用） */
    @Synchronized
    fun mergeDefaults(dbData: Map<String, BalanceData>) {
        for ((currencyId, data) in dbData) {
            // 仅补充内存中不存在的货币
            balances.putIfAbsent(currencyId.lowercase(), data)
        }
        // 补充注册表中有但数据库和内存都没有的货币
        for (currency in CurrencyRegistry.getAll()) {
            balances.putIfAbsent(currency.id.lowercase(), BalanceData(
                balance = currency.defaultBalance,
                totalEarned = BigDecimal.ZERO,
                totalSpent = BigDecimal.ZERO
            ))
        }
    }

    /** 获取需要保存的数据 */
    @Synchronized
    fun getDirtyEntries(): Map<String, BalanceData> {
        return if (dirty) balances.toMap() else emptyMap()
    }

    /** 从共享存储同步更新单个货币的缓存（跨服通知用） */
    @Synchronized
    fun updateFromStorage(currencyId: String, data: BalanceData) {
        balances[currencyId.lowercase()] = data
    }
}
