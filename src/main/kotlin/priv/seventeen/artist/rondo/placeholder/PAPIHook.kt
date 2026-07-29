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

package priv.seventeen.artist.rondo.placeholder

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import priv.seventeen.artist.blink.BlinkLog
import priv.seventeen.artist.blink.bukkitPlugin
import priv.seventeen.artist.rondo.account.AccountManager
import priv.seventeen.artist.rondo.api.CurrencyEconomySnapshot
import priv.seventeen.artist.rondo.api.RondoAPI
import priv.seventeen.artist.rondo.currency.CurrencyRegistry
import priv.seventeen.artist.rondo.ranking.RankingManager
import java.math.RoundingMode
import java.util.UUID

/**
 * PlaceholderAPI 对接
 */
object PAPIHook {

    private var registered = false
    private var expansion: RondoExpansion? = null

    fun hook() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            BlinkLog.info("PlaceholderAPI not found, skipping hook.")
            return
        }

        val candidate = RondoExpansion()
        registered = candidate.register()
        if (registered) {
            expansion = candidate
            BlinkLog.info("PlaceholderAPI hooked.")
        } else {
            BlinkLog.warn("PlaceholderAPI expansion registration failed.")
        }
    }

    fun unhook() {
        expansion?.unregister()
        expansion = null
        registered = false
    }
}

/**
 * PlaceholderAPI Expansion 实现
 *
 * 支持的占位符:
 * %rondo_balance_<id>%
 * %rondo_balance_formatted_<id>%
 * %rondo_total_earned_<id>%
 * %rondo_total_spent_<id>%
 * %rondo_top_<id>_<rank>_name%
 * %rondo_top_<id>_<rank>_balance%
 * %rondo_rank_<id>%
 */
class RondoExpansion : PlaceholderExpansion() {

    override fun getIdentifier(): String = "rondo"
    override fun getAuthor(): String = "17Artist"
    override fun getVersion(): String = bukkitPlugin.description.version
    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        if (player == null) return null

        return when {
            // %rondo_balance_<id>%
            params.startsWith("balance_formatted_") -> {
                val currencyId = params.removePrefix("balance_formatted_")
                val currency = CurrencyRegistry.get(currencyId) ?: return null
                val data = getCachedCurrency(player.uniqueId, currencyId) ?: return "---"
                currency.format(data.balance)
            }
            params.startsWith("balance_") -> {
                val currencyId = params.removePrefix("balance_")
                val currency = CurrencyRegistry.get(currencyId) ?: return null
                val data = getCachedCurrency(player.uniqueId, currencyId) ?: return "---"
                data.balance.setScale(currency.decimalPlaces, RoundingMode.HALF_UP).toPlainString()
            }

            // %rondo_total_earned_<id>%
            params.startsWith("total_earned_") -> {
                val currencyId = params.removePrefix("total_earned_")
                val currency = CurrencyRegistry.get(currencyId) ?: return null
                val data = getCachedCurrency(player.uniqueId, currencyId) ?: return "---"
                data.totalEarned
                    .setScale(currency.decimalPlaces, RoundingMode.HALF_UP)
                    .toPlainString()
            }

            // %rondo_total_spent_<id>%
            params.startsWith("total_spent_") -> {
                val currencyId = params.removePrefix("total_spent_")
                val currency = CurrencyRegistry.get(currencyId) ?: return null
                val data = getCachedCurrency(player.uniqueId, currencyId) ?: return "---"
                data.totalSpent
                    .setScale(currency.decimalPlaces, RoundingMode.HALF_UP)
                    .toPlainString()
            }

            // %rondo_top_<id>_<rank>_name% / %rondo_top_<id>_<rank>_balance%
            params.startsWith("top_") -> {
                val remaining = params.removePrefix("top_")
                // 从末尾解析 field 和 rank，剩余部分为 currency_id
                // 格式: <currency_id>_<rank>_<field>
                // field 只能是 "name" 或 "balance"（不含下划线）
                // rank 是纯数字（不含下划线）
                val lastUnderscore = remaining.lastIndexOf('_')
                if (lastUnderscore < 0) return null
                val field = remaining.substring(lastUnderscore + 1)
                val beforeField = remaining.substring(0, lastUnderscore)
                val secondLastUnderscore = beforeField.lastIndexOf('_')
                if (secondLastUnderscore < 0) return null
                val rankStr = beforeField.substring(secondLastUnderscore + 1)
                val currencyId = beforeField.substring(0, secondLastUnderscore)

                // 验证 rank 是数字，如果不是则尝试将更多部分归入 currencyId
                val rank = rankStr.toIntOrNull()
                if (rank == null) {
                    // 可能货币 ID 含下划线，尝试匹配已注册的货币
                    val registeredIds = CurrencyRegistry.getIds()
                    for (id in registeredIds) {
                        if (remaining.startsWith("${id}_")) {
                            val afterId = remaining.removePrefix("${id}_")
                            val segments = afterId.split("_")
                            if (segments.size == 2) {
                                val r = segments[0].toIntOrNull() ?: continue
                                val f = segments[1]
                                val entries = RankingManager.getCachedRanking(id)
                                val entry = entries.getOrNull(r - 1) ?: return "---"
                                return when (f) {
                                    "name" -> entry.playerName
                                    "balance" -> {
                                        val c = CurrencyRegistry.get(id)
                                        entry.balance.setScale(c?.decimalPlaces ?: 0, RoundingMode.HALF_UP).toPlainString()
                                    }
                                    else -> null
                                }
                            }
                        }
                    }
                    return null
                }

                val entries = RankingManager.getCachedRanking(currencyId)
                val entry = entries.getOrNull(rank - 1) ?: return "---"

                when (field) {
                    "name" -> entry.playerName
                    "balance" -> {
                        val currency = CurrencyRegistry.get(currencyId)
                        entry.balance.setScale(currency?.decimalPlaces ?: 0, RoundingMode.HALF_UP).toPlainString()
                    }
                    else -> null
                }
            }

            // %rondo_rank_<id>%
            params.startsWith("rank_") -> {
                val currencyId = params.removePrefix("rank_")
                val rank = RondoAPI.getPlayerRank(player.uniqueId, currencyId)
                rank?.toString() ?: "未上榜"
            }

            else -> null
        }
    }

    private fun getCachedCurrency(
        playerUuid: UUID,
        currencyId: String
    ): CurrencyEconomySnapshot? {
        return AccountManager.getOrRequestEconomySnapshot(playerUuid)
            ?.getCurrency(currencyId)
    }
}
