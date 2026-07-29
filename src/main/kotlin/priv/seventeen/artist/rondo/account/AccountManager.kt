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

import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitRunnable
import priv.seventeen.artist.blink.BlinkLog
import priv.seventeen.artist.blink.bukkitPlugin
import priv.seventeen.artist.rondo.api.CurrencyEconomySnapshot
import priv.seventeen.artist.rondo.api.PlayerEconomySnapshot
import priv.seventeen.artist.rondo.config.MainConfig
import priv.seventeen.artist.rondo.currency.CurrencyRegistry
import priv.seventeen.artist.rondo.currency.MoneyConstraints
import priv.seventeen.artist.rondo.redis.RedisManager
import priv.seventeen.artist.rondo.storage.AtomicBalanceResult
import priv.seventeen.artist.rondo.storage.BalanceData
import priv.seventeen.artist.rondo.storage.BalanceEntry
import priv.seventeen.artist.rondo.storage.ExchangeBalanceRequest
import priv.seventeen.artist.rondo.storage.StorageManager
import priv.seventeen.artist.rondo.storage.TransferBalanceRequest
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

/**
 * 账户管理器
 */
object AccountManager {

    private const val LOCK_STRIPES = 256
    private const val READ_LOAD_RETRY_MILLIS = 5_000L

    private val accounts = ConcurrentHashMap<UUID, Account>()
    private val accountLocks = Array(LOCK_STRIPES) { ReentrantLock() }
    private val persistenceGate = ReentrantReadWriteLock()
    private val loadGenerations = ConcurrentHashMap<UUID, Long>()
    private val mutationVersions = ConcurrentHashMap<UUID, AtomicLong>()
    private val readCache = EconomyReadCache()
    private val readLoads = ConcurrentHashMap<UUID, CompletableFuture<PlayerEconomySnapshot>>()
    private val readLoadRetryAfter = ConcurrentHashMap<UUID, Long>()

    @Volatile
    private var crossServer = false

    @Volatile
    private var writeThrough = true

    @Volatile
    private var running = false

    @Volatile
    private var offlineSnapshotTtlMillis = 300_000L

    fun initialize(config: MainConfig) {
        running = true
        crossServer = config.crossServer.enabled
        writeThrough = crossServer || config.storage.writeThrough
        offlineSnapshotTtlMillis = config.performance.readCacheOfflineTtlSeconds * 1_000L
        readCache.configure(config.performance.readCacheMaxOfflineEntries)

        if (!writeThrough) {
            object : BukkitRunnable() {
                override fun run() {
                    saveAll()
                }
            }.runTaskTimerAsynchronously(
                bukkitPlugin,
                config.performance.saveInterval.toLong(),
                config.performance.saveInterval.toLong()
            )
        }

        if (offlineSnapshotTtlMillis > 0L) {
            object : BukkitRunnable() {
                override fun run() {
                    readCache.removeExpired()
                }
            }.runTaskTimerAsynchronously(bukkitPlugin, 1_200L, 1_200L)
        }
    }

    fun getAccount(playerUuid: UUID): Account? = accounts[playerUuid]

    /** 非阻塞探测展示快照；未命中时返回 null */
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
     * 同步取得缓存或从存储加载玩家全部货币快照。
     *
     * 仅用于必须立即返回准确值的同步协议（例如 Vault 冷查询）；可能阻塞数据库。
     */
    fun getOrLoadEconomySnapshot(playerUuid: UUID): PlayerEconomySnapshot {
        peekEconomySnapshot(playerUuid)?.let { return it }
        readLoads[playerUuid]?.let { return it.join() }
        if (!running || !bukkitPlugin.isEnabled) {
            throw IllegalStateException("Rondo 尚未启用或正在关闭")
        }

        val retryAfter = readLoadRetryAfter[playerUuid] ?: 0L
        if (System.currentTimeMillis() < retryAfter) {
            throw IllegalStateException("经济快照后台加载正在退避")
        }

        return try {
            withPlayerLock(playerUuid) {
                peekEconomySnapshot(playerUuid) ?: loadEconomySnapshotLocked(playerUuid)
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

    /** 异步取得缓存或从存储加载玩家全部货币快照。 */
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
                        if (!running || !bukkitPlugin.isEnabled) {
                            throw IllegalStateException("Rondo 尚未启用或正在关闭")
                        }
                        peekEconomySnapshot(playerUuid) ?: loadEconomySnapshotLocked(playerUuid)
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
     * 异步加载玩家。generation + mutationVersion 防止玩家已退出或加载期间发生离线写入时，
     * 旧快照重新覆盖新余额。
     */
    fun loadPlayer(playerUuid: UUID, playerName: String) {
        val generation = loadGenerations.merge(playerUuid, 1L, Long::plus) ?: 1L
        scheduleLoad(playerUuid, playerName, generation, 0)
    }

    private fun scheduleLoad(
        playerUuid: UUID,
        playerName: String,
        generation: Long,
        attempt: Int
    ) {
        val versionBefore = mutationVersion(playerUuid)
        object : BukkitRunnable() {
            override fun run() {
                try {
                    val (data, versionAfterRead) = persistenceGate.readLock().withLock {
                        if (!running || !bukkitPlugin.isEnabled) {
                            throw IllegalStateException("Rondo 尚未启用或正在关闭")
                        }
                        StorageManager.provider.savePlayerName(playerUuid, playerName)
                        StorageManager.provider.loadBalances(playerUuid) to mutationVersion(playerUuid)
                    }

                    object : BukkitRunnable() {
                        override fun run() {
                            if (loadGenerations[playerUuid] != generation) return
                            if (versionBefore != versionAfterRead ||
                                mutationVersion(playerUuid) != versionAfterRead
                            ) {
                                scheduleLoad(playerUuid, playerName, generation, 0)
                                return
                            }
                            withPlayerLock(playerUuid) {
                                if (loadGenerations[playerUuid] != generation ||
                                    mutationVersion(playerUuid) != versionAfterRead
                                ) {
                                    scheduleLoad(playerUuid, playerName, generation, 0)
                                    return@withPlayerLock
                                }
                                Account(playerUuid).also {
                                    it.load(data)
                                    accounts[playerUuid] = it
                                    publishAccountSnapshot(playerUuid, it)
                                }
                            }
                        }
                    }.runTask(bukkitPlugin)
                } catch (e: Exception) {
                    if (!running || !bukkitPlugin.isEnabled) return
                    val retryTicks = (20L shl attempt.coerceAtMost(5)).coerceAtMost(600L)
                    BlinkLog.warn(
                        "加载玩家经济账户失败 $playerUuid，将在 ${retryTicks / 20} 秒后重试: ${e.message}"
                    )
                    object : BukkitRunnable() {
                        override fun run() {
                            if (bukkitPlugin.isEnabled &&
                                loadGenerations[playerUuid] == generation &&
                                Bukkit.getPlayer(playerUuid)?.isOnline == true
                            ) {
                                scheduleLoad(playerUuid, playerName, generation, attempt + 1)
                            }
                        }
                    }.runTaskLater(bukkitPlugin, retryTicks)
                }
            }
        }.runTaskAsynchronously(bukkitPlugin)
    }

    /** 玩家下线后失效所有尚未完成的加载，并保存单服内存快照。 */
    fun unloadPlayer(playerUuid: UUID) {
        loadGenerations.remove(playerUuid)
        val removed = withPlayerLock(playerUuid) {
            val account = accounts[playerUuid] ?: return@withPlayerLock true
            try {
                if (!writeThrough && account.dirty) {
                    StorageManager.provider.saveBalancesBatch(
                        account.getAllBalances().map { (currencyId, data) ->
                            BalanceEntry(playerUuid, currencyId, data)
                        }
                    )
                    account.markClean()
                }
                accounts.remove(playerUuid, account)
            } catch (e: Exception) {
                // 保留脏账户，交给定时保存继续重试，避免退出瞬间的数据库抖动丢失资金。
                account.markDirty()
                BlinkLog.error("玩家退出时保存余额失败，已保留待重试账户 $playerUuid", e)
                false
            }
        }
        if (removed) {
            readCache.markOffline(playerUuid, offlineSnapshotTtlMillis)
            mutationVersions.remove(playerUuid)
        }
    }

    fun <T> withPlayerLock(playerUuid: UUID, block: () -> T): T {
        return persistenceGate.readLock().withLock {
            lockFor(playerUuid).withLock(block)
        }
    }

    fun <T> withPlayerLocks(firstUuid: UUID, secondUuid: UUID, block: () -> T): T {
        return persistenceGate.readLock().withLock {
            val firstLock = lockFor(firstUuid)
            val secondLock = lockFor(secondUuid)
            if (firstLock === secondLock) return@withLock firstLock.withLock(block)

            val ordered = if (lockIndex(firstUuid) < lockIndex(secondUuid)) {
                firstLock to secondLock
            } else {
                secondLock to firstLock
            }
            ordered.first.withLock {
                ordered.second.withLock(block)
            }
        }
    }

    fun depositOffline(
        playerUuid: UUID,
        currencyId: String,
        amount: BigDecimal,
        source: String
    ): Boolean = withPlayerLock(playerUuid) {
        val currency = CurrencyRegistry.get(currencyId) ?: return@withPlayerLock false
        if (amount <= BigDecimal.ZERO) return@withPlayerLock false

        val success = if (!writeThrough && accounts[playerUuid] != null) {
            accounts.getValue(playerUuid).deposit(currency.id, amount)
        } else {
            StorageManager.provider.updateOfflineBalance(
                playerUuid = playerUuid,
                currencyId = currency.id,
                delta = amount,
                source = source,
                maxBalance = currency.maxBalance.takeIf { currency.hasMaxBalance }
                    ?: MoneyConstraints.MAX_ABSOLUTE_BALANCE,
                initialBalance = currency.defaultBalance
            )
        }
        afterMutation(playerUuid, currency.id, success)
        success
    }

    fun withdrawOffline(
        playerUuid: UUID,
        currencyId: String,
        amount: BigDecimal,
        source: String
    ): Boolean = withPlayerLock(playerUuid) {
        val currency = CurrencyRegistry.get(currencyId) ?: return@withPlayerLock false
        if (amount <= BigDecimal.ZERO) return@withPlayerLock false

        val success = if (!writeThrough && accounts[playerUuid] != null) {
            accounts.getValue(playerUuid).withdraw(currency.id, amount)
        } else {
            StorageManager.provider.updateOfflineBalance(
                playerUuid = playerUuid,
                currencyId = currency.id,
                delta = amount.negate(),
                source = source,
                allowNegative = currency.negativeAllowed,
                initialBalance = currency.defaultBalance
            )
        }
        afterMutation(playerUuid, currency.id, success)
        success
    }

    @Suppress("UNUSED_PARAMETER")
    fun setBalanceOffline(
        playerUuid: UUID,
        currencyId: String,
        amount: BigDecimal,
        source: String
    ): Boolean = withPlayerLock(playerUuid) {
        val currency = CurrencyRegistry.get(currencyId) ?: return@withPlayerLock false
        if (!MoneyConstraints.isStorable(amount) ||
            (!currency.negativeAllowed && amount < BigDecimal.ZERO) ||
            (currency.hasMaxBalance && amount > currency.maxBalance)
        ) {
            return@withPlayerLock false
        }

        val success = if (!writeThrough && accounts[playerUuid] != null) {
            accounts.getValue(playerUuid).setBalance(currency.id, amount)
        } else {
            StorageManager.provider.setOfflineBalance(playerUuid, currency.id, amount)
        }
        afterMutation(playerUuid, currency.id, success)
        success
    }

    fun getBalance(playerUuid: UUID, currencyId: String): BigDecimal {
        val currency = CurrencyRegistry.get(currencyId) ?: return BigDecimal.ZERO
        return withPlayerLock(playerUuid) {
            if (!crossServer && !writeThrough) {
                accounts[playerUuid]?.getBalance(currency.id)
                    ?: StorageManager.provider.getOfflineBalance(playerUuid, currency.id)?.balance
                    ?: currency.defaultBalance
            } else {
                StorageManager.provider.getOfflineBalance(playerUuid, currency.id)?.balance
                    ?: currency.defaultBalance
            }
        }
    }

    fun getBalanceData(playerUuid: UUID, currencyId: String): BalanceData {
        val currency = CurrencyRegistry.get(currencyId) ?: return BalanceData()
        return withPlayerLock(playerUuid) {
            if (!crossServer && !writeThrough) {
                accounts[playerUuid]?.getBalanceData(currency.id)
                    ?: StorageManager.provider.getOfflineBalance(playerUuid, currency.id)
                    ?: BalanceData(balance = currency.defaultBalance)
            } else {
                StorageManager.provider.getOfflineBalance(playerUuid, currency.id)
                    ?: BalanceData(balance = currency.defaultBalance)
            }
        }
    }

    /**
     * 原子转账。调用方应在事件触发前后使用同一组玩家锁；本方法可独立调用，
     * ReentrantLock 会安全重入。
     */
    fun transfer(
        request: TransferBalanceRequest
    ): AtomicBalanceResult = withPlayerLocks(request.from, request.to) {
        flushAccountsForAtomicOperation(setOf(request.from, request.to))
        val result = StorageManager.provider.transferBalances(request)
        if (result.success) {
            markMutated(request.from)
            markMutated(request.to)
            refreshLocalCache(request.from, request.currencyId)
            refreshLocalCache(request.to, request.currencyId)
            publishSync(request.from, request.currencyId)
            publishSync(request.to, request.currencyId)
        }
        result
    }

    fun exchange(
        request: ExchangeBalanceRequest
    ): AtomicBalanceResult = withPlayerLock(request.playerUuid) {
        flushAccountsForAtomicOperation(setOf(request.playerUuid))
        val result = StorageManager.provider.exchangeBalances(request)
        if (result.success) {
            markMutated(request.playerUuid)
            refreshLocalCache(request.playerUuid, request.fromCurrencyId)
            refreshLocalCache(request.playerUuid, request.toCurrencyId)
            publishSync(request.playerUuid, request.fromCurrencyId)
            publishSync(request.playerUuid, request.toCurrencyId)
        }
        result
    }

    /** 单服模式：保存所有脏数据到数据库。 */
    fun saveAll() {
        persistenceGate.writeLock().withLock {
            saveAllWhileExclusive()
        }
    }

    private fun saveAllWhileExclusive() {
        val entries = mutableListOf<BalanceEntry>()
        val capturedAccounts = mutableSetOf<Account>()
        for ((uuid, account) in accounts) {
            lockFor(uuid).withLock {
                val snapshot = account.snapshotAndClean()
                if (snapshot.isNotEmpty()) {
                    capturedAccounts += account
                    snapshot.forEach { (currencyId, data) ->
                        entries += BalanceEntry(uuid, currencyId, data)
                    }
                }
            }
        }
        if (entries.isEmpty()) return

        try {
            StorageManager.provider.saveBalancesBatch(entries)
        } catch (e: Exception) {
            BlinkLog.warn("批量保存余额失败: ${e.message}")
            capturedAccounts.forEach(Account::markDirty)
        }
    }

    fun shutdown() {
        running = false
        persistenceGate.writeLock().withLock {
            if (!writeThrough) {
                saveAllWhileExclusive()
                // 若首次保存失败，最后再尝试一次；失败时日志会明确保留人工处理入口。
                if (accounts.values.any { it.dirty }) saveAllWhileExclusive()
            }
            accounts.clear()
            loadGenerations.clear()
            mutationVersions.clear()
            readCache.clear()
            val failure = IllegalStateException("Rondo 正在关闭")
            readLoads.values.forEach { it.completeExceptionally(failure) }
            readLoads.clear()
            readLoadRetryAfter.clear()
        }
    }

    fun isOnline(playerUuid: UUID): Boolean = accounts.containsKey(playerUuid)

    /**
     * Redis 通知线程调用：从共享 MySQL 重新读取，而不是信任消息载荷。
     */
    fun refreshFromStorage(playerUuid: UUID, currencyId: String) {
        if (!running) return
        val currency = CurrencyRegistry.get(currencyId) ?: return
        withPlayerLock(playerUuid) {
            if (!running) return@withPlayerLock
            val account = accounts[playerUuid]
            if (account != null) {
                val data = StorageManager.provider.getOfflineBalance(playerUuid, currency.id)
                    ?: BalanceData(balance = currency.defaultBalance)
                account.updateFromStorage(currency.id, data)
                markMutated(playerUuid)
                publishAccountSnapshot(playerUuid, account)
            } else if (readCache.get(playerUuid) != null) {
                val data = StorageManager.provider.loadBalances(playerUuid)
                readCache.publishOffline(
                    createSnapshot(playerUuid, data),
                    offlineSnapshotTtlMillis
                )
            }
        }
    }

    /**
     * Redis 重新订阅后异步重读所有在线账户，修复断线窗口内丢失的失效通知。
     */
    fun reconcileOnlineSnapshotsAfterRedisReconnect() {
        if (!running || !bukkitPlugin.isEnabled) return
        val playerUuids = accounts.keys.toList()
        if (playerUuids.isEmpty()) return

        object : BukkitRunnable() {
            override fun run() {
                if (!running || !bukkitPlugin.isEnabled) return
                var refreshed = 0
                for (playerUuid in playerUuids) {
                    if (!running || !bukkitPlugin.isEnabled) break
                    try {
                        withPlayerLock(playerUuid) {
                            if (!running || !bukkitPlugin.isEnabled) return@withPlayerLock
                            val account = accounts[playerUuid] ?: return@withPlayerLock
                            val data = StorageManager.provider.loadBalances(playerUuid)
                            account.load(data)
                            markMutated(playerUuid)
                            publishAccountSnapshot(playerUuid, account)
                            refreshed++
                        }
                    } catch (failure: Throwable) {
                        BlinkLog.warn("Redis 重连后刷新经济快照失败 $playerUuid: ${failure.message}")
                    }
                }
                BlinkLog.info("Redis 重连后已校准 §b$refreshed §f个在线经济快照")
            }
        }.runTaskAsynchronously(bukkitPlugin)
    }

    /** 货币配置重载后重建在线玩家展示快照，不访问数据库。 */
    fun rebuildOnlineSnapshots() {
        for ((playerUuid, account) in accounts) {
            withPlayerLock(playerUuid) {
                if (accounts[playerUuid] === account) {
                    publishAccountSnapshot(playerUuid, account)
                }
            }
        }
    }

    private fun flushAccountsForAtomicOperation(playerUuids: Set<UUID>) {
        if (writeThrough) return
        val entries = mutableListOf<BalanceEntry>()
        val dirtyAccounts = mutableListOf<Account>()
        for (uuid in playerUuids) {
            val account = accounts[uuid] ?: continue
            val dirty = account.getDirtyEntries()
            if (dirty.isNotEmpty()) {
                dirtyAccounts += account
                dirty.forEach { (currencyId, data) ->
                    entries += BalanceEntry(uuid, currencyId, data)
                }
            }
        }
        if (entries.isEmpty()) return

        try {
            StorageManager.provider.saveBalancesBatch(entries)
            dirtyAccounts.forEach(Account::markClean)
        } catch (e: Exception) {
            dirtyAccounts.forEach(Account::markDirty)
            throw e
        }
    }

    private fun afterMutation(playerUuid: UUID, currencyId: String, success: Boolean) {
        if (!success) return
        markMutated(playerUuid)
        if (writeThrough) {
            refreshLocalCache(playerUuid, currencyId)
        } else {
            accounts[playerUuid]?.let { publishAccountSnapshot(playerUuid, it) }
        }
        if (crossServer) {
            publishSync(playerUuid, currencyId)
        }
    }

    private fun refreshLocalCache(playerUuid: UUID, currencyId: String) {
        val currency = CurrencyRegistry.get(currencyId) ?: return
        val account = accounts[playerUuid]
        if (account != null) {
            val data = StorageManager.provider.getOfflineBalance(playerUuid, currency.id)
                ?: BalanceData(balance = currency.defaultBalance)
            account.updateFromStorage(currency.id, data)
            publishAccountSnapshot(playerUuid, account)
        } else {
            val data = StorageManager.provider.loadBalances(playerUuid)
            readCache.publishOffline(
                createSnapshot(playerUuid, data),
                offlineSnapshotTtlMillis
            )
        }
    }

    private fun publishSync(playerUuid: UUID, currencyId: String) {
        if (crossServer && RedisManager.isEnabled) {
            RedisManager.publishSync(playerUuid, currencyId)
        }
    }

    private fun mutationVersion(playerUuid: UUID): Long {
        return mutationVersions[playerUuid]?.get() ?: 0L
    }

    private fun markMutated(playerUuid: UUID) {
        if (accounts.containsKey(playerUuid) || loadGenerations.containsKey(playerUuid)) {
            mutationVersions.computeIfAbsent(playerUuid) { AtomicLong() }.incrementAndGet()
        }
    }

    private fun publishAccountSnapshot(
        playerUuid: UUID,
        account: Account
    ): PlayerEconomySnapshot {
        return createSnapshot(playerUuid, account.getAllBalances()).also {
            readCache.publishOnline(it)
        }
    }

    /** 调用方必须持有玩家锁。 */
    private fun loadEconomySnapshotLocked(playerUuid: UUID): PlayerEconomySnapshot {
        val onlineAccount = accounts[playerUuid]
        if (onlineAccount != null) {
            return publishAccountSnapshot(playerUuid, onlineAccount)
        }

        val data = StorageManager.provider.loadBalances(playerUuid)
        return createSnapshot(playerUuid, data).also {
            readCache.publishOffline(it, offlineSnapshotTtlMillis)
        }
    }

    private fun createSnapshot(
        playerUuid: UUID,
        storedData: Map<String, BalanceData>
    ): PlayerEconomySnapshot {
        val currencies = linkedMapOf<String, CurrencyEconomySnapshot>()
        for (currency in CurrencyRegistry.getAll()) {
            val data = storedData[currency.id]
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
            revision = mutationVersion(playerUuid),
            updatedAtEpochMillis = System.currentTimeMillis()
        )
    }

    private fun <T> failedFuture(failure: Throwable): CompletableFuture<T> {
        return CompletableFuture<T>().also { it.completeExceptionally(failure) }
    }

    private fun lockFor(playerUuid: UUID): ReentrantLock = accountLocks[lockIndex(playerUuid)]

    private fun lockIndex(playerUuid: UUID): Int {
        return (playerUuid.hashCode() and Int.MAX_VALUE) % LOCK_STRIPES
    }
}
