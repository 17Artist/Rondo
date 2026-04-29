package priv.seventeen.artist.rondo.placeholder

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import priv.seventeen.artist.blink.BlinkLog
import priv.seventeen.artist.rondo.Rondo
import priv.seventeen.artist.rondo.api.RondoAPI
import priv.seventeen.artist.rondo.currency.CurrencyRegistry
import priv.seventeen.artist.rondo.ranking.RankingManager

/**
 * PlaceholderAPI 对接
 */
object PAPIHook {

    private var registered = false

    fun hook() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            BlinkLog.info("PlaceholderAPI not found, skipping hook.")
            return
        }

        RondoExpansion().register()
        registered = true
        BlinkLog.info("PlaceholderAPI hooked.")
    }

    fun unhook() {
        // PlaceholderAPI 会在插件卸载时自动注销
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
    override fun getVersion(): String = "1.0.0"
    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        if (player == null) return null

        val parts = params.split("_")
        if (parts.isEmpty()) return null

        return when {
            // %rondo_balance_<id>%
            params.startsWith("balance_formatted_") -> {
                val currencyId = params.removePrefix("balance_formatted_")
                val currency = CurrencyRegistry.get(currencyId) ?: return null
                val balance = RondoAPI.getBalance(player.uniqueId, currencyId)
                currency.format(balance)
            }
            params.startsWith("balance_") -> {
                val currencyId = params.removePrefix("balance_")
                val currency = CurrencyRegistry.get(currencyId) ?: return null
                val balance = RondoAPI.getBalance(player.uniqueId, currencyId)
                balance.setScale(currency.decimalPlaces).toPlainString()
            }

            // %rondo_total_earned_<id>%
            params.startsWith("total_earned_") -> {
                val currencyId = params.removePrefix("total_earned_")
                val currency = CurrencyRegistry.get(currencyId) ?: return null
                val account = priv.seventeen.artist.rondo.account.AccountManager.getAccount(player.uniqueId)
                val data = account?.getBalanceData(currencyId)
                data?.totalEarned?.setScale(currency.decimalPlaces)?.toPlainString() ?: "0"
            }

            // %rondo_total_spent_<id>%
            params.startsWith("total_spent_") -> {
                val currencyId = params.removePrefix("total_spent_")
                val currency = CurrencyRegistry.get(currencyId) ?: return null
                val account = priv.seventeen.artist.rondo.account.AccountManager.getAccount(player.uniqueId)
                val data = account?.getBalanceData(currencyId)
                data?.totalSpent?.setScale(currency.decimalPlaces)?.toPlainString() ?: "0"
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
                                        entry.balance.setScale(c?.decimalPlaces ?: 0).toPlainString()
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
                        entry.balance.setScale(currency?.decimalPlaces ?: 0).toPlainString()
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
}
