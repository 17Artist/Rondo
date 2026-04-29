package priv.seventeen.artist.rondo.ranking

import java.math.BigDecimal
import java.util.UUID

/**
 * 排行榜条目
 */
data class RankingEntry(
    val rank: Int,
    val playerUuid: UUID,
    val playerName: String,
    val balance: BigDecimal
)
