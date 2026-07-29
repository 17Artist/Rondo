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
