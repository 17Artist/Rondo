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

package priv.seventeen.artist.rondo.log

import org.bukkit.scheduler.BukkitRunnable
import priv.seventeen.artist.blink.BlinkLog
import priv.seventeen.artist.blink.bukkitPlugin
import priv.seventeen.artist.rondo.config.MainConfig
import priv.seventeen.artist.rondo.storage.StorageManager
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 流水日志管理器
 */
object LogManager {

    private const val BATCH_SIZE = 500

    private val queue = ConcurrentLinkedDeque<TransactionLog>()
    private val queueSize = AtomicInteger()
    private val flushLock = ReentrantLock()
    private val recoveryLock = ReentrantLock()

    @Volatile
    private var enabled = true
    private var maxQueueSize = 1_000
    private var consecutiveFailures = 0

    fun initialize(config: MainConfig) {
        enabled = config.features.transactionLog
        maxQueueSize = config.performance.logQueueSize
        if (!enabled) {
            BlinkLog.info("Transaction log disabled.")
            return
        }

        object : BukkitRunnable() {
            override fun run() {
                flush()
            }
        }.runTaskTimerAsynchronously(bukkitPlugin, 100L, 100L)

        val retentionDays = config.features.logRetentionDays
        if (retentionDays > 0) {
            object : BukkitRunnable() {
                override fun run() {
                    try {
                        StorageManager.provider.cleanExpiredLogs(retentionDays)
                    } catch (e: Exception) {
                        BlinkLog.warn("清理过期流水失败: ${e.message}")
                    }
                }
            }.runTaskTimerAsynchronously(bukkitPlugin, 72_000L, 72_000L)
        }
    }

    fun submit(log: TransactionLog) {
        if (!enabled) return
        val normalized = log.copy(
            source = log.source.take(128),
            detail = log.detail?.take(255)
        )

        val sizeAfter = queueSize.incrementAndGet()
        if (sizeAfter <= maxQueueSize) {
            queue.offerLast(normalized)
            return
        }
        queueSize.decrementAndGet()

        // 满载是异常状态。同步直写会牺牲本次调用延迟，但保住审计记录。
        try {
            StorageManager.provider.insertLog(normalized)
        } catch (e: Exception) {
            BlinkLog.error("流水队列已满且数据库直写失败，记录已写入恢复文件", e)
            appendRecovery(listOf(normalized))
        }
    }

    fun flush() {
        if (!enabled || !flushLock.tryLock()) return
        try {
            flushBatch()
        } finally {
            flushLock.unlock()
        }
    }

    private fun flushBatch(): Boolean {
        if (queueSize.get() == 0) return true
        val batch = ArrayList<TransactionLog>(BATCH_SIZE)
        while (batch.size < BATCH_SIZE) {
            val log = queue.pollFirst() ?: break
            queueSize.decrementAndGet()
            batch += log
        }
        if (batch.isEmpty()) return true

        return try {
            StorageManager.provider.insertLogsBatch(batch)
            consecutiveFailures = 0
            true
        } catch (e: Exception) {
            consecutiveFailures++
            // 逆序压回队首，保持原有时间顺序。
            for (index in batch.indices.reversed()) {
                queue.offerFirst(batch[index])
                queueSize.incrementAndGet()
            }
            if (consecutiveFailures <= 5 || consecutiveFailures % 12 == 0) {
                BlinkLog.warn(
                    "刷新交易流水失败（连续 $consecutiveFailures 次，队列 ${queueSize.get()}/$maxQueueSize）: ${e.message}"
                )
            }
            false
        }
    }

    fun query(
        playerUuid: UUID,
        currencyId: String?,
        page: Int,
        pageSize: Int = 10
    ): List<TransactionLog> {
        if (page < 1 || pageSize !in 1..100) return emptyList()
        return StorageManager.provider.queryLogs(playerUuid, currencyId, page, pageSize)
    }

    fun shutdown() {
        if (!enabled) return
        flushLock.withLock {
            var attempts = 0
            while (queueSize.get() > 0 && attempts < 3) {
                if (!flushBatch()) attempts++ else attempts = 0
            }

            if (queueSize.get() > 0) {
                val remaining = mutableListOf<TransactionLog>()
                while (true) {
                    val log = queue.pollFirst() ?: break
                    queueSize.decrementAndGet()
                    remaining += log
                }
                appendRecovery(remaining)
                BlinkLog.warn(
                    "数据库不可用，${remaining.size} 条未写入流水已保存到 transaction-recovery.log"
                )
            }
        }
    }

    private fun appendRecovery(logs: List<TransactionLog>) {
        if (logs.isEmpty()) return
        recoveryLock.withLock {
            val file = File(bukkitPlugin.dataFolder, "transaction-recovery.log")
            file.parentFile?.mkdirs()
            val isNew = !file.exists() || file.length() == 0L
            FileOutputStream(file, true)
                .bufferedWriter(StandardCharsets.UTF_8)
                .use { writer ->
                if (isNew) {
                    writer.appendLine(
                        "# timestamp\\tuuid\\tcurrency\\taction\\tamount\\tbalance_after\\tsource_base64\\tdetail_base64"
                    )
                }
                for (log in logs) {
                    writer.append(log.timestamp.toString()).append('\t')
                    writer.append(log.playerUuid.toString()).append('\t')
                    writer.append(log.currencyId).append('\t')
                    writer.append(log.action.name).append('\t')
                    writer.append(log.amount.toPlainString()).append('\t')
                    writer.append(log.balanceAfter.toPlainString()).append('\t')
                    writer.append(encode(log.source)).append('\t')
                    writer.append(encode(log.detail ?: "")).appendLine()
                }
            }
        }
    }

    private fun encode(value: String): String {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    }
}
