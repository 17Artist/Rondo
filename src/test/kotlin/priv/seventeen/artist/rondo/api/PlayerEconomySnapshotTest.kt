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

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlayerEconomySnapshotTest {

    @Test
    fun `normalizes ids and exposes an immutable currency map`() {
        val snapshot = PlayerEconomySnapshot(
            playerUuid = UUID.randomUUID(),
            currencies = linkedMapOf(
                "Gold" to CurrencyEconomySnapshot(
                    balance = BigDecimal("12.50"),
                    totalEarned = BigDecimal("20"),
                    totalSpent = BigDecimal("7.50")
                )
            ),
            revision = 3,
            updatedAtEpochMillis = 1000
        )

        assertEquals(BigDecimal("12.50"), snapshot.getBalance("GOLD"))
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (snapshot.currencies as MutableMap<String, CurrencyEconomySnapshot>).clear()
        }
    }
}
