package priv.seventeen.artist.rondo.account

import org.bukkit.scheduler.BukkitRunnable
import priv.seventeen.artist.blink.BlinkLog
import priv.seventeen.artist.blink.bukkitPlugin
import priv.seventeen.artist.rondo.config.MainConfig
import priv.seventeen.artist.rondo.storage.BalanceData
import priv.seventeen.artist.rondo.storage.BalanceEntry
import priv.seventeen.artist.rondo.storage.StorageManager
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 账户管理器 — 缓存 + 加载/卸载 + 离线操作
 */
object AccountManager {

    private val accounts = ConcurrentHashMap<UUID, Account>()
    /** 正在加载中的玩家集合，防止竞态 */
    private val loading = ConcurrentHashMap.newKeySet<UUID>()

    fun initialize(config: MainConfig) {
        // 定时批量保存
        object : BukkitRunnable() {
            override fun run() {
                saveAll()
            }
        }.runTaskTimerAsynchronously(bukkitPlugin, config.performance.saveInterval.toLong(), config.performance.saveInterval.toLong())
    }

    /** 获取在线玩家账户 */
    fun getAccount(playerUuid: UUID): Account? = accounts[playerUuid]

    /** 获取或加载账户（在线优先，离线从数据库读） */
    fun getOrLoadAccount(playerUuid: UUID): Account {
        accounts[playerUuid]?.let { return it }
        // 离线玩家临时加载
        val account = Account(playerUuid)
        val data = StorageManager.provider.loadBalances(playerUuid)
        account.load(data)
        return account
    }

    /** 玩家上线加载 */
    fun loadPlayer(playerUuid: UUID) {
        loading.add(playerUuid)
        object : BukkitRunnable() {
            override fun run() {
                val data = StorageManager.provider.loadBalances(playerUuid)
                object : BukkitRunnable() {
                    override fun run() {
                        // 如果加载期间已有账户（被其他操作创建），合并而非覆盖
                        val existing = accounts[playerUuid]
                        if (existing != null && existing.dirty) {
                            // 已有脏数据，以内存为准，仅补充缺失的货币
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
        if (account.dirty) {
            object : BukkitRunnable() {
                override fun run() {
                    saveAccount(playerUuid, account)
                }
            }.runTaskAsynchronously(bukkitPlugin)
        }
    }

    /** 保存所有脏数据 */
    fun saveAll() {
        val entries = mutableListOf<BalanceEntry>()
        for ((uuid, account) in accounts) {
            // 使用 snapshot + markClean 原子操作，避免丢失脏标记
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
                // 标记回脏，下次重试
                for (entry in entries) {
                    accounts[entry.playerUuid]?.markDirty()
                }
            }
        }
    }

    /** 保存单个账户 */
    private fun saveAccount(playerUuid: UUID, account: Account) {
        for ((currencyId, data) in account.getAllBalances()) {
            StorageManager.provider.saveBalance(playerUuid, currencyId, data)
        }
    }

    /** 离线操作：存入 */
    fun depositOffline(playerUuid: UUID, currencyId: String, amount: BigDecimal, source: String): Boolean {
        // 如果在线，走内存
        val online = accounts[playerUuid]
        if (online != null) {
            return online.deposit(currencyId, amount)
        }
        // 离线走数据库
        return StorageManager.provider.updateOfflineBalance(playerUuid, currencyId, amount, source)
    }

    /** 离线操作：扣除 */
    fun withdrawOffline(playerUuid: UUID, currencyId: String, amount: BigDecimal, source: String): Boolean {
        val online = accounts[playerUuid]
        if (online != null) {
            return online.withdraw(currencyId, amount)
        }
        return StorageManager.provider.updateOfflineBalance(playerUuid, currencyId, amount.negate(), source)
    }

    /** 离线操作：设置余额 */
    @Suppress("UNUSED_PARAMETER")
    fun setBalanceOffline(playerUuid: UUID, currencyId: String, amount: BigDecimal, source: String): Boolean {
        val online = accounts[playerUuid]
        if (online != null) {
            return online.setBalance(currencyId, amount)
        }
        // 离线：先读取现有数据保留统计字段，再更新余额
        val existing = StorageManager.provider.getOfflineBalance(playerUuid, currencyId)
        val data = BalanceData(
            balance = amount,
            totalEarned = existing?.totalEarned ?: BigDecimal.ZERO,
            totalSpent = existing?.totalSpent ?: BigDecimal.ZERO
        )
        StorageManager.provider.saveBalance(playerUuid, currencyId, data)
        return true
    }

    /** 获取余额（在线或离线） */
    fun getBalance(playerUuid: UUID, currencyId: String): BigDecimal {
        val online = accounts[playerUuid]
        if (online != null) {
            return online.getBalance(currencyId)
        }
        return StorageManager.provider.getOfflineBalance(playerUuid, currencyId)?.balance ?: BigDecimal.ZERO
    }

    /** 关闭时保存所有 */
    fun shutdown() {
        saveAll()
        // 同步保存所有剩余
        for ((uuid, account) in accounts) {
            if (account.dirty) {
                saveAccount(uuid, account)
            }
        }
        accounts.clear()
    }

    /** 是否在线 */
    fun isOnline(playerUuid: UUID): Boolean = accounts.containsKey(playerUuid)
}
