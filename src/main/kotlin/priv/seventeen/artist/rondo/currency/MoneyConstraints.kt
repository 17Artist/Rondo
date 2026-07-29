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
 * 所有持久化后端共同遵守的金额边界。
 *
 * MySQL 使用 DECIMAL(20,4)，因此最多 16 位整数和 4 位小数。SQLite 也主动采用
 * 相同边界，避免切换存储后才出现溢出。
 */
object MoneyConstraints {

    const val MAX_DECIMAL_PLACES = 4

    val MAX_ABSOLUTE_BALANCE: BigDecimal = BigDecimal("9999999999999999.9999")
    val MIN_ABSOLUTE_BALANCE: BigDecimal = MAX_ABSOLUTE_BALANCE.negate()
    val MAX_CUMULATIVE_AMOUNT: BigDecimal =
        BigDecimal.TEN.pow(34).subtract(BigDecimal("0.0001"))

    fun requireStorable(value: BigDecimal, field: String): BigDecimal {
        require(value.stripTrailingZeros().scale().coerceAtLeast(0) <= MAX_DECIMAL_PLACES) {
            "$field 最多支持 $MAX_DECIMAL_PLACES 位小数"
        }
        require(value >= MIN_ABSOLUTE_BALANCE && value <= MAX_ABSOLUTE_BALANCE) {
            "$field 超出 DECIMAL(20,4) 可存储范围"
        }
        return value
    }

    fun isStorable(value: BigDecimal): Boolean {
        return value.stripTrailingZeros().scale().coerceAtLeast(0) <= MAX_DECIMAL_PLACES &&
            value >= MIN_ABSOLUTE_BALANCE &&
            value <= MAX_ABSOLUTE_BALANCE
    }

    fun isCumulativeStorable(value: BigDecimal): Boolean {
        return value.stripTrailingZeros().scale().coerceAtLeast(0) <= MAX_DECIMAL_PLACES &&
            value >= BigDecimal.ZERO &&
            value <= MAX_CUMULATIVE_AMOUNT
    }
}
