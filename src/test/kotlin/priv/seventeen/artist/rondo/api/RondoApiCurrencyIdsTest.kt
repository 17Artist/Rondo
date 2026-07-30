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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import priv.seventeen.artist.rondo.currency.Currency
import priv.seventeen.artist.rondo.currency.CurrencyRegistry
import java.math.BigDecimal

class RondoApiCurrencyIdsTest {

    @Test
    fun `returns a snapshot of all registered currency ids`() {
        val original = CurrencyRegistry.snapshot()
        try {
            CurrencyRegistry.restore(
                linkedMapOf(
                    "gold" to currency("gold"),
                    "points" to currency("points")
                )
            )

            val ids = RondoAPI.getAllCurrencyIds()
            assertEquals(linkedSetOf("gold", "points"), ids)

            CurrencyRegistry.restore(mapOf("honor" to currency("honor")))
            assertEquals(linkedSetOf("gold", "points"), ids)
            assertEquals(setOf("honor"), RondoAPI.getAllCurrencyIds())
        } finally {
            CurrencyRegistry.restore(original)
        }
    }

    @Test
    fun `has balance rejects an unregistered currency even for zero amount`() {
        val original = CurrencyRegistry.snapshot()
        try {
            CurrencyRegistry.restore(emptyMap())
            assertFalse(
                RondoAPI.hasBalance(
                    java.util.UUID.randomUUID(),
                    "missing",
                    BigDecimal.ZERO
                )
            )
        } finally {
            CurrencyRegistry.restore(original)
        }
    }

    private fun currency(id: String) = Currency(
        id = id,
        displayName = id,
        symbol = id,
        color = "",
        description = "",
        decimalPlaces = 0,
        maxBalance = BigDecimal.valueOf(-1),
        defaultBalance = BigDecimal.ZERO,
        negativeAllowed = false,
        tradeable = true,
        transferable = true,
        transferTaxRate = BigDecimal.ZERO,
        vaultPrimary = false,
        rankingEnabled = true
    )
}
