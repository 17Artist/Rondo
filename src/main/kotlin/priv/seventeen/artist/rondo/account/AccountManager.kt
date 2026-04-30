package priv.seventeen.artist.rondo.account

import org.bukkit.scheduler.BukkitRunnable
import priv.seventeen.artist.blink.BlinkLog
import priv.seventeen.artist.blink.bukkitPlugin
import priv.seventeen.artist.rondo.config.MainConfig
import priv.seventeen.artist.rondo.currency.CurrencyRegistry
import priv.seventeen.artist.rondo.redis.RedisEconomyProvider
import priv.seventeen.artist.rondo.redis.RedisManager
import priv.seventeen.artist.rondo.storage.BalanceData
import priv.seventeen.artist.rondo.storage.BalanceEntry
import priv.seventeen.artist.rondo.storage.StorageManager
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 账户管理器 — 缓存 + 加载/卸载 + 离线操作
 * 跨服模式下余额操作走 Redis 原子事务
 */
object AccountManager {

    private val accounts = ConcurrentHashMap<UUID, Account>()
    private val loading = ConcurrentHashMap.newKeySet<UUID>()
    private var crossServer = false

    fun initialize(config: MainConfig) {
        crossServer = config.crossServer.enabled

        // 单服模式：定时批量保存到数据库
        if (!crossServer) {
            object : BukkitRunnable() {
                override fun run() { saveAll() }
            }.runTaskTimerAsynchronously(bukkitPlugin, config.performance.saveInterval.toLong(), config.performance.saveInterval.toLong())
        } else {
            // 跨服模式：定时将 Redis 数据备份到 MySQL
            if (config.crossServer.mysqlBackup) {
                object : BukkitRunnable() {
                    override fun run() { backupAllToDatabase() }
                }.runTaskTimerAsynchronously(bukkitPlugin, 6000L, 6000L) // 5分钟
            }
        }
    }

    /** 获取在线玩家账户 */
    fun getAccount(playerUuid: UUID): Account? = accounts[playerUuid]

    /** 玩家上线加载 */
    fun loadPlayer(playerUuid: UUID) {
        loading.add(playerUuid)
        object : BukkitRunnable() {
            override fun run() {
                val data = if (crossServer) {
                    // 跨服：从 Redis 加载，如果 Redis 没有则从数据库加载并同步到 Redis
                    var redisData = RedisEconomyProvider.loadAllBalances(playerUuid)
                    if (redisData.isEmpty()) {
                        val dbData = StorageManager.provider.loadBalances(playerUuid)
                        if (dbData.isNotEmpty()) {
                            RedisEconomyProvider.syncFromDatabase(playerUuid, dbData)
                        }
                        dbData
                    } else {
                        redisData
                    }
                } else {
                    StorageManager.provider.loadBalances(playerUuid)
                }

                object : BukkitRunnable() {
                    override fun run() {
                        val existing = accounts[playerUuid]
                        if (existing != null && existing.dirty) {
                            existing.mergeDefaults(data)
                        } else {
                            val account = Account(playerUuid)
                            account.load(data)
                            accounts[playerUuid] = account
                        }
                        loading.remove(playerUuid)
                    }
                }.runTask(bukkitPlugin)
            }
        }.runTaskAsynchronously(bukkitPlugin)
    }

    /** 玩家下线保存并卸载 */
    fun unloadPlayer(playerUuid: UUID) {
        loading.remove(playerUuid)
        val account = accounts.remove(playerUuid) ?: return
        if (crossServer) {
            // 跨服模式：备份到数据库
            if (MainConfig.instance.crossServer.mysqlBackup) {
                object : BukkitRunnable() {
                    override fun run() { backupPlayerToDatabase(playerUuid) }
                }.runTaskAsynchronously(bukkitPlugin)
            }
        } else if (account.dirty) {
            object : BukkitRunnable() {
                override fun run() { saveAccount(playerUuid, account) }
            }.runTaskAsynchronously(bukkitPlugin)
        }
    }

    // ===== 余额操作 =====

    /** 存入 */
    fun depositOffline(playerUuid: UUID, currencyId: String, amount: BigDecimal, source: String): Boolean {
        if (crossServer) {
            val currency = CurrencyRegistry.get(currencyId) ?: return false
            return RedisEconomyProvider.deposit(playerUuid, currencyId, amount, currency)
        }
        val online = accounts[playerUuid]
        if (online != null) return online.deposit(currencyId, amount)
        return StorageManager.provider.updateOfflineBalance(playerUuid, currencyId, amount, source)
    }

    /** 扣除 */
    fun withdrawOffline(playerUuid: UUID, currencyId: String, amount: BigDecimal, source: String): Boolean {
        if (crossServer) {
            val currency = CurrencyRegistry.get(currencyId)
            val allowNegative = currency?.negativeAllowed ?: false
            return RedisEconomyProvider.withdraw(playerUuid, currencyId, amount, allowNegative)
        }
        val online = accounts[playerUuid]
        if (online != null) return online.withdraw(currencyId, amount)
        val currency = CurrencyRegistry.get(currencyId)
        val allowNegative = currency?.negativeAllowed ?: false
        return StorageManager.provider.updateOfflineBalance(playerUuid, currencyId, amount.negate(), source, allowNegative)
    }

    /** 设置余额 */
    @Suppress("UNUSED_PARAMETER")
    fun setBalanceOffline(playerUuid: UUID, currencyId: String, amount: BigDecimal, source: String): Boolean {
        if (crossServer) {
            return RedisEconomyProvider.setBalance(playerUuid, currencyId, amount)
        }
        val online = accounts[playerUuid]
        if (online != null) return online.setBalance(currencyId, amount)
        val existing = StorageManager.provider.getOfflineBalance(playerUuid, currencyId)
        val data = BalanceData(
            balance = amount,
            totalEarned = existing?.totalEarned ?: BigDecimal.ZERO,
            totalSpent = existing?.totalSpent ?: BigDecimal.ZERO
        )
        StorageManager.provider.saveBalance(playerUuid, currencyId, data)
        return true
    }

    /** 获取余额 */
    fun getBalance(playerUuid: UUID, currencyId: String): BigDecimal {
        if (crossServer) {
            return RedisEconomyProvider.getBalance(playerUuid, currencyId)
        }
        val online = accounts[playerUuid]
        if (online != null) return online.getBalance(currencyId)
        return StorageManager.provider.getOfflineBalance(playerUuid, currencyId)?.balance ?: BigDecimal.ZERO
    }

    // ===== 持久化 =====

    /** 单服模式：保存所有脏数据到数据库 */
    fun saveAll() {
        val entries = mutableListOf<BalanceEntry>()
        for ((uuid, account) in accounts) {
            val snapshot = account.snapshotAndClean()
            if (snapshot.isNotEmpty()) {
                for ((currencyId, data) in snapshot) {
                    entries.add(BalanceEntry(uuid, currencyId, data))
                }
            }
        }
        if (entries.isNotEmpty()) {
            try {
                StorageManager.provider.saveBalancesBatch(entries)
            } catch (e: Exception) {
                BlinkLog.warn("Failed to save balances: ${e.message}")
                for (entry in entries) {
                    accounts[entry.playerUuid]?.markDirty()
                }
            }
        }
    }

    /** 单服模式：保存单个账户 */
    private fun saveAccount(playerUuid: UUID, account: Account) {
        for ((currencyId, data) in account.getAllBalances()) {
            StorageManager.provider.saveBalance(playerUuid, currencyId, data)
        }
    }

    /** 跨服模式：将所有在线玩家的 Redis 数据备份到 MySQL */
    private fun backupAllToDatabase() {
        for ((uuid, _) in accounts) {
            try {
                backupPlayerToDatabase(uuid)
            } catch (e: Exception) {
                BlinkLog.warn("Failed to backup $uuid to database: ${e.message}")
            }
        }
    }

    /** 跨服模式：将单个玩家的 Redis 数据备份到 MySQL */
    private fun backupPlayerToDatabase(playerUuid: UUID) {
        val data = RedisEconomyProvider.backupToDatabase(playerUuid)
        for ((currencyId, balanceData) in data) {
            StorageManager.provider.saveBalance(playerUuid, currencyId, balanceData)
        }
    }

    /** 关闭 */
    fun shutdown() {
        if (crossServer) {
            // 跨服模式：最终备份
            if (MainConfig.instance.crossServer.mysqlBackup) {
                backupAllToDatabase()
            }
        } else {
            saveAll()
            for ((uuid, account) in accounts) {
                if (account.dirty) saveAccount(uuid, account)
            }
        }
        accounts.clear()
    }

    fun isOnline(playerUuid: UUID): Boolean = accounts.containsKey(playerUuid)
}
