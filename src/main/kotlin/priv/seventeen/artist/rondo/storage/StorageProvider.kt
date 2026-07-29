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

package priv.seventeen.artist.rondo.storage

import priv.seventeen.artist.rondo.log.TransactionLog
import java.math.BigDecimal
import java.util.UUID

/**
 * 存储提供者抽象接口
 */
interface StorageProvider {

    /** 初始化（建表等） */
    fun initialize()

    /** 关闭连接 */
    fun shutdown()

    // ===== 余额操作 =====

    /** 加载玩家所有货币余额 */
    fun loadBalances(playerUuid: UUID): Map<String, BalanceData>

    /** 保存玩家单个货币余额 */
    fun saveBalance(playerUuid: UUID, currencyId: String, data: BalanceData)

    /** 批量保存余额 */
    fun saveBalancesBatch(entries: List<BalanceEntry>)

    /**
     * 直接更新离线玩家余额（原子操作）
     * @param maxBalance 余额上限，存入时若超过则拒绝；传 null 仅跳过业务上限，
     * 仍受存储绝对边界约束
     */
    fun updateOfflineBalance(
        playerUuid: UUID,
        currencyId: String,
        delta: BigDecimal,
        source: String,
        allowNegative: Boolean = false,
        maxBalance: BigDecimal? = null,
        initialBalance: BigDecimal = BigDecimal.ZERO
    ): Boolean

    /** 原子设置余额；保留累计获得/消耗统计。 */
    fun setOfflineBalance(
        playerUuid: UUID,
        currencyId: String,
        amount: BigDecimal
    ): Boolean

    /** 获取离线玩家余额 */
    fun getOfflineBalance(playerUuid: UUID, currencyId: String): BalanceData?

    /**
     * 在一个存储事务中完成玩家间转账。
     *
     * 实现必须按稳定顺序锁定两条余额记录，避免相向转账死锁；失败时不得留下部分扣款。
     */
    fun transferBalances(request: TransferBalanceRequest): AtomicBalanceResult

    /**
     * 在一个存储事务中完成兑换、周期额度检查和兑换审计记录写入。
     */
    fun exchangeBalances(request: ExchangeBalanceRequest): AtomicBalanceResult

    /** 记录玩家当前名称，供排行榜离线展示使用。 */
    fun savePlayerName(playerUuid: UUID, playerName: String)

    // ===== 流水日志 =====

    /** 写入流水日志 */
    fun insertLog(log: TransactionLog)

    /** 批量写入流水日志 */
    fun insertLogsBatch(logs: List<TransactionLog>)

    /** 查询流水日志 */
    fun queryLogs(playerUuid: UUID, currencyId: String?, page: Int, pageSize: Int): List<TransactionLog>

    /** 清理过期日志 */
    fun cleanExpiredLogs(retentionDays: Int)

    // ===== 排行榜 =====

    /** 查询排行榜 */
    fun queryRanking(currencyId: String, limit: Int): List<RankingData>

    /** 查询玩家排名 */
    fun queryPlayerRank(playerUuid: UUID, currencyId: String): Int?

}

/**
 * 余额数据
 */
data class BalanceData(
    val balance: BigDecimal = BigDecimal.ZERO,
    val totalEarned: BigDecimal = BigDecimal.ZERO,
    val totalSpent: BigDecimal = BigDecimal.ZERO
)

/**
 * 批量保存条目
 */
data class BalanceEntry(
    val playerUuid: UUID,
    val currencyId: String,
    val data: BalanceData
)

/**
 * 排行榜数据
 */
data class RankingData(
    val playerUuid: UUID,
    val playerName: String?,
    val balance: BigDecimal
)

enum class AtomicBalanceFailure {
    INSUFFICIENT_FUNDS,
    BALANCE_LIMIT,
    PERIOD_LIMIT
}

data class AtomicBalanceResult(
    val success: Boolean,
    val failure: AtomicBalanceFailure? = null
)

data class TransferBalanceRequest(
    val from: UUID,
    val to: UUID,
    val currencyId: String,
    val debitAmount: BigDecimal,
    val creditAmount: BigDecimal,
    val allowNegative: Boolean,
    val senderInitialBalance: BigDecimal,
    val recipientInitialBalance: BigDecimal,
    val recipientMaxBalance: BigDecimal?
)

data class ExchangeBalanceRequest(
    val playerUuid: UUID,
    val ruleId: String,
    val fromCurrencyId: String,
    val toCurrencyId: String,
    val debitAmount: BigDecimal,
    val creditAmount: BigDecimal,
    val allowNegative: Boolean,
    val fromInitialBalance: BigDecimal,
    val toInitialBalance: BigDecimal,
    val toMaxBalance: BigDecimal?,
    val periodStart: Long?,
    val periodLimit: BigDecimal?
)
