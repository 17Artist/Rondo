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

package priv.seventeen.artist.rondo.api

import java.math.BigDecimal
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID

/**
 * 单个货币的只读经济快照。
 *
 * 快照只用于展示和高频读取；资金校验与变更仍必须通过 RondoAPI 的权威事务接口。
 */
data class CurrencyEconomySnapshot(
    val balance: BigDecimal,
    val totalEarned: BigDecimal,
    val totalSpent: BigDecimal
)

/**
 * 玩家全部已注册货币的不可变只读快照。
 *
 * [revision] 仅在当前在线会话内递增，不能作为跨服数据库版本号。
 */
class PlayerEconomySnapshot internal constructor(
    val playerUuid: UUID,
    currencies: Map<String, CurrencyEconomySnapshot>,
    val revision: Long,
    val updatedAtEpochMillis: Long
) {
    val currencies: Map<String, CurrencyEconomySnapshot> = Collections.unmodifiableMap(
        LinkedHashMap(currencies.mapKeys { (currencyId, _) -> currencyId.lowercase() })
    )

    fun getCurrency(currencyId: String): CurrencyEconomySnapshot? {
        return currencies[currencyId.lowercase()]
    }

    fun getBalance(currencyId: String): BigDecimal? {
        return getCurrency(currencyId)?.balance
    }
}
