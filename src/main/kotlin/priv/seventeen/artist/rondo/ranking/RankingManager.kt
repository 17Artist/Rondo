package priv.seventeen.artist.rondo.ranking

import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitRunnable
import priv.seventeen.artist.blink.BlinkLog
import priv.seventeen.artist.blink.bukkitPlugin
import priv.seventeen.artist.rondo.config.MainConfig
import priv.seventeen.artist.rondo.currency.CurrencyRegistry
import priv.seventeen.artist.rondo.storage.StorageManager
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 排行榜管理器 — 定时刷新缓存
 */
object RankingManager {

    private val rankings = ConcurrentHashMap<String, List<RankingEntry>>()
    /** 玩家名缓存，避免频繁调用 Bukkit.getOfflinePlayer */
    private val nameCache = ConcurrentHashMap<UUID, String>()
    private var rankingSize = 100

    fun initialize(config: MainConfig) {
        rankingSize = config.performance.rankingSize
        val refreshInterval = config.performance.rankingRefresh.toLong()

        // 定时刷新排行榜
        object : BukkitRunnable() {
            override fun run() {
                refreshAll()
            }
        }.runTaskTimerAsynchronously(bukkitPlugin, 100L, refreshInterval)
    }

    /** 获取排行榜 */
    fun getRanking(currencyId: String, page: Int, pageSize: Int): List<RankingEntry> {
        val list = rankings[currencyId] ?: return emptyList()
        val start = (page - 1) * pageSize
        if (start >= list.size) return emptyList()
        val end = minOf(start + pageSize, list.size)
        return list.subList(start, end)
    }

    /** 获取排行榜总页数 */
    fun getTotalPages(currencyId: String, pageSize: Int): Int {
        val list = rankings[currencyId] ?: return 0
        return (list.size + pageSize - 1) / pageSize
    }

    /** 获取玩家排名 */
    fun getPlayerRank(player: UUID, currencyId: String): Int? {
        val list = rankings[currencyId] ?: return null
        val entry = list.firstOrNull { it.playerUuid == player }
        return entry?.rank ?: StorageManager.provider.queryPlayerRank(player, currencyId)
    }

    /** 刷新所有排行榜 */
    fun refreshAll() {
        // 先刷新在线玩家名缓存
        for (player in Bukkit.getOnlinePlayers()) {
            nameCache[player.uniqueId] = player.name
        }

        for (currency in CurrencyRegistry.getAll()) {
            if (!currency.rankingEnabled) continue
            try {
                val data = StorageManager.provider.queryRanking(currency.id, rankingSize)
                val entries = data.mapIndexed { index, rankingData ->
                    RankingEntry(
                        rank = index + 1,
                        playerUuid = rankingData.playerUuid,
                        playerName = resolvePlayerName(rankingData.playerUuid),
                        balance = rankingData.balance
                    )
                }
                rankings[currency.id] = entries
            } catch (e: Exception) {
                BlinkLog.warn("Failed to refresh ranking for ${currency.id}: ${e.message}")
            }
        }
    }

    /** 解析玩家名（优先缓存，避免网络请求） */
    private fun resolvePlayerName(uuid: UUID): String {
        nameCache[uuid]?.let { return it }
        // 尝试从 Bukkit 获取（可能触发缓存查找，但不会发网络请求如果已缓存）
        val name = Bukkit.getOfflinePlayer(uuid).name
        if (name != null) {
            nameCache[uuid] = name
        }
        return name ?: "Unknown"
    }

    /** 获取缓存的排行榜数据 */
    fun getCachedRanking(currencyId: String): List<RankingEntry> {
        return rankings[currencyId] ?: emptyList()
    }
}
