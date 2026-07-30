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

package priv.seventeen.artist.rondo.ranking

import org.bukkit.scheduler.BukkitRunnable
import priv.seventeen.artist.blink.BlinkLog
import priv.seventeen.artist.blink.bukkitPlugin
import priv.seventeen.artist.rondo.config.MainConfig
import priv.seventeen.artist.rondo.currency.CurrencyRegistry
import priv.seventeen.artist.rondo.storage.StorageManager
import java.util.UUID

/**
 * 排行榜管理器 — 定时刷新缓存
 */
object RankingManager {

    @Volatile
    private var rankings: Map<String, List<RankingEntry>> = emptyMap()
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
        if (page < 1 || pageSize !in 1..100) return emptyList()
        val list = rankings[currencyId.lowercase()] ?: return emptyList()
        val startLong = (page.toLong() - 1L) * pageSize.toLong()
        if (startLong >= list.size) return emptyList()
        val start = startLong.toInt()
        val end = minOf(start + pageSize, list.size)
        return list.subList(start, end)
    }

    /** 获取排行榜总页数 */
    fun getTotalPages(currencyId: String, pageSize: Int): Int {
        if (pageSize !in 1..100) return 0
        val list = rankings[currencyId.lowercase()] ?: return 0
        return (list.size + pageSize - 1) / pageSize
    }

    /**
     * 获取玩家排名（仅基于已缓存的排行榜，避免在主线程发起阻塞式数据库查询）。
     * 若玩家不在缓存的前 rankingSize 名内，返回 null（视为未上榜）。
     */
    fun getPlayerRank(player: UUID, currencyId: String): Int? {
        val list = rankings[currencyId.lowercase()] ?: return null
        return list.firstOrNull { it.playerUuid == player }?.rank
    }

    /** 刷新所有排行榜 */
    fun refreshAll() {
        val refreshed = linkedMapOf<String, List<RankingEntry>>()
        for (currency in CurrencyRegistry.getAll()) {
            if (!currency.rankingEnabled) continue
            try {
                val data = StorageManager.provider.queryRanking(currency.id, rankingSize)
                val entries = data.mapIndexed { index, rankingData ->
                    RankingEntry(
                        rank = index + 1,
                        playerUuid = rankingData.playerUuid,
                        playerName = rankingData.playerName
                            ?: rankingData.playerUuid.toString().substring(0, 8),
                        balance = rankingData.balance
                    )
                }
                refreshed[currency.id] = entries
            } catch (e: Exception) {
                BlinkLog.warn("Failed to refresh ranking for ${currency.id}: ${e.message}")
                rankings[currency.id]?.let { refreshed[currency.id] = it }
            }
        }
        rankings = refreshed.toMap()
    }

    /** 获取缓存的排行榜数据 */
    fun getCachedRanking(currencyId: String): List<RankingEntry> {
        return rankings[currencyId.lowercase()] ?: emptyList()
    }
}
