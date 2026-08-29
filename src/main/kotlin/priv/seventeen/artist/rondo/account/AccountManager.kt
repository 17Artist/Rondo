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

import org.bukkit.scheduler.BukkitRunnable
import priv.seventeen.artist.blink.BlinkLog
import priv.seventeen.artist.blink.bukkitPlugin
import priv.seventeen.artist.rondo.api.CurrencyEconomySnapshot
import priv.seventeen.artist.rondo.api.PlayerEconomySnapshot
import priv.seventeen.artist.rondo.config.MainConfig
import priv.seventeen.artist.rondo.currency.CurrencyRegistry
import priv.seventeen.artist.rondo.currency.MoneyConstraints
import priv.seventeen.artist.rondo.redis.RedisManager
import priv.seventeen.artist.rondo.storage.AtomicBalanceFailure
import priv.seventeen.artist.rondo.storage.AtomicBalanceResult
import priv.seventeen.artist.rondo.storage.BalanceData
import priv.seventeen.artist.rondo.storage.CommittedBalance
import priv.seventeen.artist.rondo.storage.ExchangeBalanceRequest
import priv.seventeen.artist.rondo.storage.StorageManager
import priv.seventeen.artist.rondo.storage.TransferBalanceRequest
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 权威资金事务与只读快照的协调器
 */
object AccountManager {

    private const val LOCK_STRIPES = 256
    private const val READ_LOAD_RETRY_MILLIS = 5_000L

    private data class PlayerState(
        val session: Long,
        var revision: Long
    )

    private val accountLocks = Array(LOCK_STRIPES) { ReentrantLock() }
    private val onlineStates = ConcurrentHashMap<UUID, PlayerState>()
    private val sessionSequence = AtomicLong()
    private val readCache = EconomyReadCache()
    private val readLoads = ConcurrentHashMap<UUID, CompletableFuture<PlayerEconomySnapshot>>()
    private val readLoadRetryAfter = ConcurrentHashMap<UUID, Long>()

    @Volatile
    private var crossServer = false

    @Volatile
    private var running = false

    @Volatile
    private var offlineSnapshotTtlMillis = 300_000L

    fun initialize(config: MainConfig) {
        crossServer = config.crossServer.enabled
        offlineSnapshotTtlMillis = config.performance.readCacheOfflineTtlSeconds * 1_000L
        readCache.configure(config.performance.readCacheMaxOfflineEntries)
        running = true

        object : BukkitRunnable() {
            override fun run() {
                if (offlineSnapshotTtlMillis > 0L) {
                    readCache.removeExpired()
                }
                val now = System.currentTimeMillis()
                readLoadRetryAfter.forEach { (playerUuid, retryAfter) ->
                    if (retryAfter <= now) {
                        readLoadRetryAfter.remove(playerUuid, retryAfter)
                    }
                }
            }
        }.runTaskTimerAsynchronously(bukkitPlugin, 1_200L, 1_200L)
    }

    /** 非阻塞探测展示快照；未命中时返回 null。 */
    fun peekEconomySnapshot(playerUuid: UUID): PlayerEconomySnapshot? {
        return readCache.get(playerUuid)
    }

    /**
     * 非阻塞读取展示快照；未命中时在后台发起一次去重加载。
     * 适合 PlaceholderAPI 等不能等待数据库的同步展示入口。
     */
    fun getOrRequestEconomySnapshot(playerUuid: UUID): PlayerEconomySnapshot? {
        val cached = peekEconomySnapshot(playerUuid)
        if (cached != null) return cached

        val retryAfter = readLoadRetryAfter[playerUuid] ?: 0L
        if (System.currentTimeMillis() >= retryAfter) {
            loadEconomySnapshotAsync(playerUuid)
        }
        return null
    }

    /**
     * 同步取得缓存或从权威存储加载玩家全部货币快照。
     *
     * 仅用于必须立即返回值的同步协议（例如 Vault 冷查询）；可能阻塞数据库。
     */
    fun getOrLoadEconomySnapshot(playerUuid: UUID): PlayerEconomySnapshot {
        peekEconomySnapshot(playerUuid)?.let { return it }
        ensureRunning()

        val retryAfter = readLoadRetryAfter[playerUuid] ?: 0L
        if (System.currentTimeMillis() < retryAfter) {
            throw IllegalStateException("经济快照后台加载正在退避")
        }

        return try {
            withPlayerLock(playerUuid) {
                peekEconomySnapshot(playerUuid) ?: loadAndPublishSnapshotLocked(playerUuid)
            }.also {
                readLoadRetryAfter.remove(playerUuid)
            }
        } catch (failure: Throwable) {
            if (running) {
                readLoadRetryAfter[playerUuid] =
                    System.currentTimeMillis() + READ_LOAD_RETRY_MILLIS
            }
            throw failure
        }
    }

    /** 异步取得缓存或从权威存储加载玩家全部货币快照。 */
    fun loadEconomySnapshotAsync(playerUuid: UUID): CompletableFuture<PlayerEconomySnapshot> {
        peekEconomySnapshot(playerUuid)?.let { return CompletableFuture.completedFuture(it) }
        if (!running || !bukkitPlugin.isEnabled) {
            return failedFuture(IllegalStateException("Rondo 尚未启用或正在关闭"))
        }

        val retryAfter = readLoadRetryAfter[playerUuid] ?: 0L
        if (System.currentTimeMillis() < retryAfter) {
            return failedFuture(IllegalStateException("经济快照后台加载正在退避"))
        }

        val candidate = CompletableFuture<PlayerEconomySnapshot>()
        val existing = readLoads.putIfAbsent(playerUuid, candidate)
        if (existing != null) return existing

        val loadTask = object : BukkitRunnable() {
            override fun run() {
                try {
                    val snapshot = withPlayerLock(playerUuid) {
                        ensureRunning()
                        peekEconomySnapshot(playerUuid)
                            ?: loadAndPublishSnapshotLocked(playerUuid)
                    }
                    readLoadRetryAfter.remove(playerUuid)
                    candidate.complete(snapshot)
                } catch (failure: Throwable) {
                    if (running) {
                        readLoadRetryAfter[playerUuid] =
                            System.currentTimeMillis() + READ_LOAD_RETRY_MILLIS
                        BlinkLog.warn("后台加载玩家经济快照失败 $playerUuid: ${failure.message}")
                    }
                    candidate.completeExceptionally(failure)
                } finally {
                    readLoads.remove(playerUuid, candidate)
                }
            }
        }

        try {
            loadTask.runTaskAsynchronously(bukkitPlugin)
        } catch (failure: Throwable) {
            readLoads.remove(playerUuid, candidate)
            if (running) {
                readLoadRetryAfter[playerUuid] =
                    System.currentTimeMillis() + READ_LOAD_RETRY_MILLIS
            }
            candidate.completeExceptionally(failure)
        }
        return candidate
    }

    /**
     * 开启新的在线会话，并异步校准权威快照。
     *
     * 会话编号和修订号共同阻止旧加入任务覆盖快速重连或后续资金事务。
     */
    fun loadPlayer(playerUuid: UUID, playerName: String) {
        if (!running) return
        val session = sessionSequence.incrementAndGet()
        val expectedRevision = withPlayerLock(playerUuid) {
            val cached = readCache.get(playerUuid)
            val revision = cached?.revision ?: 0L
            onlineStates[playerUuid] = PlayerState(session, revision)
            if (cached != null) readCache.publishOnline(cached)
            revision
        }
        schedulePlayerNameUpdate(playerUuid, playerName, session, attempt = 0)
        schedulePlayerLoad(playerUuid, session, expectedRevision, attempt = 0)
    }

    private fun schedulePlayerNameUpdate(
        playerUuid: UUID,
        playerName: String,
        session: Long,
        attempt: Int
    ) {
        object : BukkitRunnable() {
            override fun run() {
                try {
                    if (!isCurrentSession(playerUuid, session)) return
                    StorageManager.provider.savePlayerName(playerUuid, playerName)
                } catch (failure: Throwable) {
                    if (!running || !bukkitPlugin.isEnabled ||
                        !isCurrentSession(playerUuid, session)
                    ) {
                        return
                    }
                    val retryTicks = (20L shl attempt.coerceAtMost(5)).coerceAtMost(600L)
                    BlinkLog.warn(
                        "更新玩家经济账户名称失败 $playerUuid，将在 ${retryTicks / 20} 秒后重试: " +
                            failure.message
                    )
                    object : BukkitRunnable() {
                        override fun run() {
                            if (isCurrentSession(playerUuid, session)) {
                                schedulePlayerNameUpdate(
                                    playerUuid,
                                    playerName,
                                    session,
                                    attempt + 1
                                )
                            }
                        }
                    }.runTaskLater(bukkitPlugin, retryTicks)
                }
            }
        }.runTaskAsynchronously(bukkitPlugin)
    }

    private fun schedulePlayerLoad(
        playerUuid: UUID,
        session: Long,
        expectedRevision: Long,
        attempt: Int
    ) {
        object : BukkitRunnable() {
            override fun run() {
                try {
                    ensureRunning()
                    val data = StorageManager.provider.loadBalances(playerUuid)
                    object : BukkitRunnable() {
                        override fun run() {
                            var retryRevision: Long? = null
                            withPlayerLock(playerUuid) {
                                val state = onlineStates[playerUuid]
                                if (state == null || state.session != session) return@withPlayerLock
                                if (state.revision != expectedRevision) {
                                    val current = readCache.get(playerUuid)
                                    if (current != null) {
                                        readCache.publishOnline(current)
                                    } else {
                                        retryRevision = state.revision
                                    }
                                    return@withPlayerLock
                                }
                                publishStoredDataLocked(playerUuid, data)
                            }
                            retryRevision?.let {
                                schedulePlayerLoad(playerUuid, session, it, attempt = 0)
                            }
                        }
                    }.runTask(bukkitPlugin)
                } catch (failure: Throwable) {
                    if (!running || !bukkitPlugin.isEnabled ||
                        !isCurrentSession(playerUuid, session)
                    ) {
                        return
                    }
                    val retryTicks = (20L shl attempt.coerceAtMost(5)).coerceAtMost(600L)
                    BlinkLog.warn(
                        "加载玩家经济快照失败 $playerUuid，将在 ${retryTicks / 20} 秒后重试: " +
                            failure.message
                    )
                    object : BukkitRunnable() {
                        override fun run() {
                            val revision = withPlayerLock(playerUuid) {
                                onlineStates[playerUuid]
                                    ?.takeIf { it.session == session }
                                    ?.revision
                            }
                            if (revision != null) {
                                schedulePlayerLoad(
                                    playerUuid,
                                    session,
                                    revision,
                                    attempt + 1
                                )
                            }
                        }
                    }.runTaskLater(bukkitPlugin, retryTicks)
                }
            }
        }.runTaskAsynchronously(bukkitPlugin)
    }

    /** 结束在线会话；没有任何待保存余额。 */
    fun unloadPlayer(playerUuid: UUID) {
        withPlayerLock(playerUuid) {
            onlineStates.remove(playerUuid)
            readCache.markOffline(playerUuid, offlineSnapshotTtlMillis)
        }
    }

    /** 同步查询权威余额，可能访问 SQLite/MySQL。 */
    fun getBalance(playerUuid: UUID, currencyId: String): BigDecimal {
        val currency = CurrencyRegistry.get(currencyId) ?: return BigDecimal.ZERO
        return withPlayerLock(playerUuid) {
            StorageManager.provider.getBalance(playerUuid, currency.id)?.balance
                ?: currency.defaultBalance
        }
    }

    /** 同步查询权威余额及累计值，可能访问 SQLite/MySQL。 */
    fun getBalanceData(playerUuid: UUID, currencyId: String): BalanceData {
        val currency = CurrencyRegistry.get(currencyId) ?: return BalanceData()
        return withPlayerLock(playerUuid) {
            StorageManager.provider.getBalance(playerUuid, currency.id)
                ?: BalanceData(balance = currency.defaultBalance)
        }
    }

    fun deposit(
        playerUuid: UUID,
        currencyId: String,
        amount: BigDecimal
    ): AtomicBalanceResult {
        val currency = CurrencyRegistry.get(currencyId)
            ?: return AtomicBalanceResult(false, AtomicBalanceFailure.BALANCE_LIMIT)
        val result = withPlayerLock(playerUuid) {
            val committed = StorageManager.provider.updateBalance(
                playerUuid = playerUuid,
                currencyId = currency.id,
                delta = amount,
                maxBalance = currency.maxBalance.takeIf { currency.hasMaxBalance }
                    ?: MoneyConstraints.MAX_ABSOLUTE_BALANCE,
                initialBalance = currency.defaultBalance
            )
            applyCommittedSafelyLocked(committed)
            committed
        }
        publishCommittedInvalidations(result)
        return result
    }

    fun withdraw(
        playerUuid: UUID,
        currencyId: String,
        amount: BigDecimal
    ): AtomicBalanceResult {
        val currency = CurrencyRegistry.get(currencyId)
            ?: return AtomicBalanceResult(false, AtomicBalanceFailure.INSUFFICIENT_FUNDS)
        val result = withPlayerLock(playerUuid) {
            val committed = StorageManager.provider.updateBalance(
                playerUuid = playerUuid,
                currencyId = currency.id,
                delta = amount.negate(),
                allowNegative = currency.negativeAllowed,
                initialBalance = currency.defaultBalance
            )
            applyCommittedSafelyLocked(committed)
            committed
        }
        publishCommittedInvalidations(result)
        return result
    }

    fun setBalance(
        playerUuid: UUID,
        currencyId: String,
        amount: BigDecimal
    ): AtomicBalanceResult {
        val currency = CurrencyRegistry.get(currencyId)
            ?: return AtomicBalanceResult(false, AtomicBalanceFailure.BALANCE_LIMIT)
        val result = withPlayerLock(playerUuid) {
            val committed = StorageManager.provider.setBalance(playerUuid, currency.id, amount)
            applyCommittedSafelyLocked(committed)
            committed
        }
        publishCommittedInvalidations(result)
        return result
    }

    fun transfer(request: TransferBalanceRequest): AtomicBalanceResult {
        val result = withPlayerLocks(request.from, request.to) {
            val committed = StorageManager.provider.transferBalances(request)
            applyCommittedSafelyLocked(committed)
            committed
        }
        publishCommittedInvalidations(result)
        return result
    }

    fun exchange(request: ExchangeBalanceRequest): AtomicBalanceResult {
        val result = withPlayerLock(request.playerUuid) {
            val committed = StorageManager.provider.exchangeBalances(request)
            applyCommittedSafelyLocked(committed)
            committed
        }
        publishCommittedInvalidations(result)
        return result
    }

    fun isOnline(playerUuid: UUID): Boolean = onlineStates.containsKey(playerUuid)

    /**
     * Redis 订阅线程调用：消息只表示缓存失效，实际值重新读取共享 MySQL。
     */
    fun refreshFromStorage(playerUuid: UUID, currencyId: String) {
        if (!running) return
        val currency = CurrencyRegistry.get(currencyId) ?: return
        withPlayerLock(playerUuid) {
            if (!running) return@withPlayerLock
            val existing = readCache.get(playerUuid)
            val online = onlineStates.containsKey(playerUuid)
            if (existing == null && !online) return@withPlayerLock

            try {
                if (existing == null) {
                    publishStoredDataLocked(
                        playerUuid,
                        StorageManager.provider.loadBalances(playerUuid)
                    )
                } else {
                    val data = StorageManager.provider.getBalance(playerUuid, currency.id)
                        ?: BalanceData(balance = currency.defaultBalance)
                    publishPatchedSnapshotLocked(
                        playerUuid,
                        existing,
                        listOf(CommittedBalance(playerUuid, currency.id, data))
                    )
                }
            } catch (failure: Throwable) {
                invalidateSnapshotLocked(playerUuid)
                BlinkLog.warn("刷新远端经济快照失败 $playerUuid/${currency.id}: ${failure.message}")
            }
        }
    }

    /** Redis 重新订阅后重读全部在线账户，修复断线窗口内丢失的失效通知。 */
    fun reconcileOnlineSnapshotsAfterRedisReconnect() {
        if (!running || !bukkitPlugin.isEnabled) return
        val sessions = onlineStates.mapValues { it.value.session }
        if (sessions.isEmpty()) return

        object : BukkitRunnable() {
            override fun run() {
                var refreshed = 0
                for ((playerUuid, session) in sessions) {
                    if (!running || !bukkitPlugin.isEnabled) break
                    try {
                        withPlayerLock(playerUuid) {
                            if (!isCurrentSession(playerUuid, session)) return@withPlayerLock
                            publishStoredDataLocked(
                                playerUuid,
                                StorageManager.provider.loadBalances(playerUuid)
                            )
                            refreshed++
                        }
                    } catch (failure: Throwable) {
                        BlinkLog.warn("Redis 重连后刷新经济快照失败 $playerUuid: ${failure.message}")
                    }
                }
                BlinkLog.info(
                    "[redis-reconcile] refreshed=$refreshed; " +
                        "Redis 重连后已校准 §b$refreshed §f个在线经济快照"
                )
            }
        }.runTaskAsynchronously(bukkitPlugin)
    }

    /** 货币配置重载后按新注册表重建在线玩家展示快照，不访问数据库。 */
    fun rebuildOnlineSnapshots() {
        for (playerUuid in onlineStates.keys) {
            withPlayerLock(playerUuid) {
                val existing = readCache.get(playerUuid) ?: return@withPlayerLock
                publishPatchedSnapshotLocked(playerUuid, existing, emptyList())
            }
        }
    }

    fun shutdown() {
        running = false
        onlineStates.clear()
        readCache.clear()
        val failure = IllegalStateException("Rondo 正在关闭")
        readLoads.values.forEach { it.completeExceptionally(failure) }
        readLoads.clear()
        readLoadRetryAfter.clear()
    }

    private fun applyCommittedSafelyLocked(result: AtomicBalanceResult) {
        if (!result.success || result.committedBalances.isEmpty()) return
        try {
            result.committedBalances.groupBy { it.playerUuid }.forEach { (playerUuid, balances) ->
                val existing = readCache.get(playerUuid)
                if (existing != null) {
                    publishPatchedSnapshotLocked(playerUuid, existing, balances)
                } else if (onlineStates.containsKey(playerUuid)) {
                    // 在线冷缓存需要完整账户；事务返回值只包含本次涉及的货币。
                    publishStoredDataLocked(
                        playerUuid,
                        StorageManager.provider.loadBalances(playerUuid)
                    )
                }
            }
        } catch (failure: Throwable) {
            result.committedBalances
                .map { it.playerUuid }
                .distinct()
                .forEach(::invalidateSnapshotLocked)
            BlinkLog.warn(
                "资金事务已提交，但刷新只读快照失败；后续查询将重新加载: ${failure.message}"
            )
        }
    }

    private fun publishCommittedInvalidations(result: AtomicBalanceResult) {
        if (!result.success || !crossServer) return
        result.committedBalances
            .map { it.playerUuid to it.currencyId.lowercase() }
            .distinct()
            .forEach { (playerUuid, currencyId) ->
                if (RedisManager.isEnabled) {
                    RedisManager.publishSync(playerUuid, currencyId)
                }
            }
    }

    private fun publishPatchedSnapshotLocked(
        playerUuid: UUID,
        existing: PlayerEconomySnapshot,
        committed: List<CommittedBalance>
    ): PlayerEconomySnapshot {
        val stored = existing.currencies.mapValues { (_, data) ->
            BalanceData(data.balance, data.totalEarned, data.totalSpent)
        }.toMutableMap()
        committed.forEach { stored[it.currencyId.lowercase()] = it.data }
        return publishStoredDataLocked(playerUuid, stored)
    }

    private fun loadAndPublishSnapshotLocked(playerUuid: UUID): PlayerEconomySnapshot {
        return publishStoredDataLocked(
            playerUuid,
            StorageManager.provider.loadBalances(playerUuid)
        )
    }

    private fun publishStoredDataLocked(
        playerUuid: UUID,
        storedData: Map<String, BalanceData>
    ): PlayerEconomySnapshot {
        val revision = nextRevisionLocked(playerUuid)
        val snapshot = createSnapshot(playerUuid, storedData, revision)
        if (onlineStates.containsKey(playerUuid)) {
            readCache.publishOnline(snapshot)
        } else {
            readCache.publishOffline(snapshot, offlineSnapshotTtlMillis)
        }
        return snapshot
    }

    /**
     * 让旧的异步加载结果失效后再移除快照。
     *
     * 即使事务后的快照重建失败，已提交事务也必须推进在线会话修订号，避免入服预载把
     * 提交前读取的旧数据重新发布。
     */
    private fun invalidateSnapshotLocked(playerUuid: UUID) {
        if (onlineStates.containsKey(playerUuid)) {
            nextRevisionLocked(playerUuid)
        }
        readCache.invalidate(playerUuid)
    }

    private fun nextRevisionLocked(playerUuid: UUID): Long {
        val state = onlineStates[playerUuid]
        val current = maxOf(state?.revision ?: 0L, readCache.get(playerUuid)?.revision ?: 0L)
        val next = if (current == Long.MAX_VALUE) 1L else current + 1L
        if (state != null) state.revision = next
        return next
    }

    private fun createSnapshot(
        playerUuid: UUID,
        storedData: Map<String, BalanceData>,
        revision: Long
    ): PlayerEconomySnapshot {
        val normalized = storedData.mapKeys { (currencyId, _) -> currencyId.lowercase() }
        val currencies = linkedMapOf<String, CurrencyEconomySnapshot>()
        for (currency in CurrencyRegistry.getAll()) {
            val data = normalized[currency.id]
                ?: BalanceData(balance = currency.defaultBalance)
            currencies[currency.id] = CurrencyEconomySnapshot(
                balance = data.balance,
                totalEarned = data.totalEarned,
                totalSpent = data.totalSpent
            )
        }
        return PlayerEconomySnapshot(
            playerUuid = playerUuid,
            currencies = currencies,
            revision = revision,
            updatedAtEpochMillis = System.currentTimeMillis()
        )
    }

    private fun ensureRunning() {
        if (!running || !bukkitPlugin.isEnabled) {
            throw IllegalStateException("Rondo 尚未启用或正在关闭")
        }
    }

    private fun isCurrentSession(playerUuid: UUID, session: Long): Boolean {
        return running && onlineStates[playerUuid]?.session == session
    }

    private fun <T> withPlayerLock(playerUuid: UUID, block: () -> T): T {
        return lockFor(playerUuid).withLock(block)
    }

    private fun <T> withPlayerLocks(
        firstUuid: UUID,
        secondUuid: UUID,
        block: () -> T
    ): T {
        val firstLock = lockFor(firstUuid)
        val secondLock = lockFor(secondUuid)
        if (firstLock === secondLock) return firstLock.withLock(block)

        val ordered = if (lockIndex(firstUuid) < lockIndex(secondUuid)) {
            firstLock to secondLock
        } else {
            secondLock to firstLock
        }
        return ordered.first.withLock {
            ordered.second.withLock(block)
        }
    }

    private fun <T> failedFuture(failure: Throwable): CompletableFuture<T> {
        return CompletableFuture<T>().also { it.completeExceptionally(failure) }
    }

    private fun lockFor(playerUuid: UUID): ReentrantLock = accountLocks[lockIndex(playerUuid)]

    private fun lockIndex(playerUuid: UUID): Int {
        return (playerUuid.hashCode() and Int.MAX_VALUE) % LOCK_STRIPES
    }
}
