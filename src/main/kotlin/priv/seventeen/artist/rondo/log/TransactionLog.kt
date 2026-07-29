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

package priv.seventeen.artist.rondo.log

import java.math.BigDecimal
import java.util.UUID

/**
 * 交易流水日志
 */
data class TransactionLog(
    val playerUuid: UUID,
    val currencyId: String,
    val action: Action,
    val amount: BigDecimal,
    val balanceAfter: BigDecimal,
    val source: String,
    val detail: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class Action {
        DEPOSIT,
        WITHDRAW,
        TRANSFER_IN,
        TRANSFER_OUT,
        SET,
        EXCHANGE_IN,
        EXCHANGE_OUT
    }
}
