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

package priv.seventeen.artist.rondo.storage

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class SQLiteProviderTest {

    @TempDir
    lateinit var tempDir: Path

    private var provider: SQLiteProvider? = null

    @AfterEach
    fun closeProvider() {
        provider?.shutdown()
    }

    @Test
    fun `offline mutation starts from configured default balance`() {
        val storage = createProvider()
        val player = UUID.randomUUID()

        assertTrue(
            storage.updateOfflineBalance(
                playerUuid = player,
                currencyId = "gold",
                delta = bd("5.25"),
                source = "test",
                maxBalance = bd("100"),
                initialBalance = bd("10")
            )
        )

        assertEquals(bd("15.25"), storage.getOfflineBalance(player, "gold")?.balance)
        assertEquals(bd("5.25"), storage.getOfflineBalance(player, "gold")?.totalEarned)
    }

    @Test
    fun `transfer commits both balances or neither`() {
        val storage = createProvider()
        val from = UUID.randomUUID()
        val to = UUID.randomUUID()
        val request = TransferBalanceRequest(
            from = from,
            to = to,
            currencyId = "gold",
            debitAmount = bd("30"),
            creditAmount = bd("25"),
            allowNegative = false,
            senderInitialBalance = bd("100"),
            recipientInitialBalance = bd("10"),
            recipientMaxBalance = bd("40")
        )

        assertTrue(storage.transferBalances(request).success)
        assertEquals(bd("70"), storage.getOfflineBalance(from, "gold")?.balance)
        assertEquals(bd("35"), storage.getOfflineBalance(to, "gold")?.balance)

        val failed = storage.transferBalances(request.copy(recipientMaxBalance = bd("50")))
        assertFalse(failed.success)
        assertEquals(AtomicBalanceFailure.BALANCE_LIMIT, failed.failure)
        assertEquals(bd("70"), storage.getOfflineBalance(from, "gold")?.balance)
        assertEquals(bd("35"), storage.getOfflineBalance(to, "gold")?.balance)
    }

    @Test
    fun `exchange quota and both balances share one transaction`() {
        val storage = createProvider()
        val player = UUID.randomUUID()
        val request = ExchangeBalanceRequest(
            playerUuid = player,
            ruleId = "gold_to_points",
            fromCurrencyId = "gold",
            toCurrencyId = "points",
            debitAmount = bd("10"),
            creditAmount = bd("3"),
            allowNegative = false,
            fromInitialBalance = bd("100"),
            toInitialBalance = bd("0"),
            toMaxBalance = bd("100"),
            periodStart = 0L,
            periodLimit = bd("5")
        )

        assertTrue(storage.exchangeBalances(request).success)
        val failed = storage.exchangeBalances(request)
        assertFalse(failed.success)
        assertEquals(AtomicBalanceFailure.PERIOD_LIMIT, failed.failure)
        assertEquals(bd("90"), storage.getOfflineBalance(player, "gold")?.balance)
        assertEquals(bd("3"), storage.getOfflineBalance(player, "points")?.balance)
    }

    @Test
    fun `ranking keeps decimal ordering beyond double integer precision`() {
        val storage = createProvider()
        val lower = UUID.randomUUID()
        val higher = UUID.randomUUID()
        storage.setOfflineBalance(lower, "gold", bd("9007199254740992.0001"))
        storage.setOfflineBalance(higher, "gold", bd("9007199254740992.0002"))

        val ranking = storage.queryRanking("gold", 2)
        assertEquals(higher, ranking[0].playerUuid)
        assertEquals(lower, ranking[1].playerUuid)
    }

    @Test
    fun `concurrent withdrawals never overdraw`() {
        val storage = createProvider()
        val player = UUID.randomUUID()
        storage.setOfflineBalance(player, "gold", bd("10"))
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = executor.invokeAll(
                List(100) {
                    Callable {
                        storage.updateOfflineBalance(
                            playerUuid = player,
                            currencyId = "gold",
                            delta = BigDecimal.ONE.negate(),
                            source = "test",
                            allowNegative = false
                        )
                    }
                }
            )
            assertEquals(10, results.count { it.get() })
            assertEquals(BigDecimal.ZERO, storage.getOfflineBalance(player, "gold")?.balance)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun createProvider(): SQLiteProvider {
        return SQLiteProvider(tempDir.resolve("rondo-test.db").toFile(), logInitialization = false)
            .also {
                provider = it
                it.initialize()
            }
    }

    private fun bd(value: String): BigDecimal = BigDecimal(value)
}
