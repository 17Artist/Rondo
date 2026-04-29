package priv.seventeen.artist.rondo.currency

import java.math.BigDecimal

/**
 * 兑换规则数据类
 */
data class ExchangeRule(
    /** 规则 ID */
    val id: String,
    /** 源货币 ID */
    val fromCurrency: String,
    /** 目标货币 ID */
    val toCurrency: String,
    /** 兑换比率（源货币数量 : 1目标货币） */
    val rate: BigDecimal,
    /** 最小兑换数量（目标货币） */
    val minAmount: BigDecimal,
    /** 周期内最大兑换数量（-1=无限） */
    val maxPerPeriod: Int,
    /** 限购周期 */
    val period: ExchangePeriod,
    /** 是否启用 */
    val enabled: Boolean
) {
    /** 计算需要的源货币数量 */
    fun calculateCost(targetAmount: BigDecimal): BigDecimal {
        return targetAmount.multiply(rate)
    }
}

/**
 * 兑换限购周期
 */
enum class ExchangePeriod {
    NONE,
    DAILY,
    WEEKLY,
    MONTHLY
}
