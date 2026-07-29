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
import java.math.RoundingMode

/**
 * 货币数据类 — 从配置文件加载
 */
data class Currency(
    /** 货币唯一标识 */
    val id: String,
    /** 显示名称 */
    val displayName: String,
    /** 符号 */
    val symbol: String,
    /** 颜色代码 */
    val color: String,
    /** 描述 */
    val description: String,
    /** 小数位数 */
    val decimalPlaces: Int,
    /** 余额上限（-1=无上限） */
    val maxBalance: BigDecimal,
    /** 新玩家初始余额 */
    val defaultBalance: BigDecimal,
    /** 是否允许负数余额 */
    val negativeAllowed: Boolean,
    /** 是否可交易 */
    val tradeable: Boolean,
    /** 是否可转账 */
    val transferable: Boolean,
    /** 转账税率 */
    val transferTaxRate: BigDecimal,
    /** 是否注册为 Vault 主货币 */
    val vaultPrimary: Boolean,
    /** 是否参与排行榜 */
    val rankingEnabled: Boolean
) {
    /** 格式化金额显示 */
    fun format(amount: BigDecimal): String {
        val formatted = amount.setScale(decimalPlaces, RoundingMode.HALF_UP).toPlainString()
        return "$color$symbol$formatted"
    }

    /** 是否有余额上限 */
    val hasMaxBalance: Boolean get() = maxBalance.compareTo(BigDecimal.valueOf(-1)) != 0
}
