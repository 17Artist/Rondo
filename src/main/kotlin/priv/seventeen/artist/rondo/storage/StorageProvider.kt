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
     * @param maxBalance 余额上限，存入时若超过则拒绝；传 null 表示不校验上限（用于回滚等强制写入）
     */
    fun updateOfflineBalance(playerUuid: UUID, currencyId: String, delta: BigDecimal, source: String, allowNegative: Boolean = false, maxBalance: BigDecimal? = null): Boolean

    /** 获取离线玩家余额 */
    fun getOfflineBalance(playerUuid: UUID, currencyId: String): BalanceData?

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

    // ===== 兑换记录 =====

    /** 插入兑换记录 */
    fun insertExchangeRecord(playerUuid: UUID, ruleId: String, amount: BigDecimal)

    /** 查询周期内兑换总量 */
    fun queryExchangeCount(playerUuid: UUID, ruleId: String, sinceTimestamp: Long): BigDecimal
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
