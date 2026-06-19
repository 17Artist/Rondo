package priv.seventeen.artist.rondo.log

import priv.seventeen.artist.blink.BlinkLog
import priv.seventeen.artist.blink.bukkitPlugin
import priv.seventeen.artist.rondo.config.MainConfig
import priv.seventeen.artist.rondo.storage.StorageManager
import org.bukkit.scheduler.BukkitRunnable
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 流水日志管理器 — 异步队列写入
 */
object LogManager {

    private val queue = ConcurrentLinkedQueue<TransactionLog>()
    private var enabled = true
    private var consecutiveFailures = 0
    private const val MAX_FAILURES = 5
    private const val MAX_QUEUE_SIZE = 10000

    fun initialize(config: MainConfig) {
        enabled = config.features.transactionLog
        if (!enabled) {
            BlinkLog.info("Transaction log disabled.")
            return
        }

        // 定时批量写入
        object : BukkitRunnable() {
            override fun run() {
                flush()
            }
        }.runTaskTimerAsynchronously(bukkitPlugin, 100L, 100L)

        // 定时清理过期日志
        val retentionDays = config.features.logRetentionDays
        if (retentionDays > 0) {
            object : BukkitRunnable() {
                override fun run() {
                    StorageManager.provider.cleanExpiredLogs(retentionDays)
                }
            }.runTaskTimerAsynchronously(bukkitPlugin, 72000L, 72000L) // 每小时清理一次
        }
    }

    /** 提交一条日志 */
    fun submit(log: TransactionLog) {
        if (!enabled) return
        // 防止队列无限增长
        if (queue.size >= MAX_QUEUE_SIZE) {
            queue.poll() // 丢弃最旧的
        }
        queue.offer(log)
    }

    /** 刷新队列到数据库 */
    fun flush() {
        if (queue.isEmpty()) return
        val batch = mutableListOf<TransactionLog>()
        while (queue.isNotEmpty() && batch.size < 500) {
            queue.poll()?.let { batch.add(it) }
        }
        if (batch.isNotEmpty()) {
            try {
                StorageManager.provider.insertLogsBatch(batch)
                consecutiveFailures = 0
            } catch (e: Exception) {
                consecutiveFailures++
                if (consecutiveFailures <= MAX_FAILURES) {
                    BlinkLog.warn("Failed to flush transaction logs (attempt $consecutiveFailures/$MAX_FAILURES): ${e.message}")
                    // 重新入队
                    batch.forEach { queue.offer(it) }
                } else {
                    // 超过重试次数：丢弃前先将明细打印到控制台，避免审计记录被静默丢失
                    BlinkLog.warn("Failed to flush transaction logs $MAX_FAILURES times, dumping ${batch.size} entries to console before discarding:")
                    for (log in batch) {
                        BlinkLog.warn("  [lost-log] ${log.timestamp} ${log.playerUuid} ${log.currencyId} ${log.action} ${log.amount.toPlainString()} -> ${log.balanceAfter.toPlainString()} (${log.source})")
                    }
                    consecutiveFailures = 0
                }
            }
        }
    }

    /** 查询日志 */
    fun query(playerUuid: UUID, currencyId: String?, page: Int, pageSize: Int = 10): List<TransactionLog> {
        return StorageManager.provider.queryLogs(playerUuid, currencyId, page, pageSize)
    }

    /** 关闭时刷新所有 */
    fun shutdown() {
        flush()
    }
}
