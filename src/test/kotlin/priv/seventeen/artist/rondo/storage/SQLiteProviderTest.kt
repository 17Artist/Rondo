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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import priv.seventeen.artist.rondo.currency.MoneyConstraints
import priv.seventeen.artist.rondo.log.TransactionLog
import java.math.BigDecimal
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
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
    fun `cold mutation starts from configured default balance`() {
        val storage = createProvider()
        val player = UUID.randomUUID()

        val result = storage.updateBalance(
            playerUuid = player,
            currencyId = "gold",
            delta = bd("5.25"),
            maxBalance = bd("100"),
            initialBalance = bd("10")
        )

        assertTrue(result.success)
        assertEquals(
            BalanceData(balance = bd("15.25"), totalEarned = bd("5.25")),
            result.getCommittedBalance(player, "gold")
        )
        assertEquals(bd("15.25"), storage.getBalance(player, "gold")?.balance)
        assertEquals(bd("5.25"), storage.getBalance(player, "gold")?.totalEarned)
    }

    @Test
    fun `set balance returns committed data and preserves cumulative totals`() {
        val storage = createProvider()
        val player = UUID.randomUUID()
        assertTrue(
            storage.updateBalance(
                playerUuid = player,
                currencyId = "gold",
                delta = bd("5"),
                initialBalance = bd("10")
            ).success
        )

        val result = storage.setBalance(player, "gold", bd("99"))

        assertTrue(result.success)
        assertEquals(
            BalanceData(
                balance = bd("99"),
                totalEarned = bd("5"),
                totalSpent = BigDecimal.ZERO
            ),
            result.getCommittedBalance(player, "gold")
        )
    }

    @Test
    fun `cumulative limits reject mutation without committed balances`() {
        val storage = createProvider()
        val earnedPlayer = UUID.randomUUID()
        val spentPlayer = UUID.randomUUID()
        withRawConnection { conn ->
            conn.prepareStatement("""
                INSERT INTO rondo_balance
                    (player_uuid, currency_id, balance, total_earned, total_spent)
                VALUES (?, 'gold', '0', ?, '0')
            """.trimIndent()).use { ps ->
                ps.setString(1, earnedPlayer.toString())
                ps.setString(2, MoneyConstraints.MAX_CUMULATIVE_AMOUNT.toPlainString())
                ps.executeUpdate()
            }
            conn.prepareStatement("""
                INSERT INTO rondo_balance
                    (player_uuid, currency_id, balance, total_earned, total_spent)
                VALUES (?, 'gold', '0', '0', ?)
            """.trimIndent()).use { ps ->
                ps.setString(1, spentPlayer.toString())
                ps.setString(2, MoneyConstraints.MAX_CUMULATIVE_AMOUNT.toPlainString())
                ps.executeUpdate()
            }
        }

        val earned = storage.updateBalance(
            playerUuid = earnedPlayer,
            currencyId = "gold",
            delta = bd("0.0001")
        )
        val spent = storage.updateBalance(
            playerUuid = spentPlayer,
            currencyId = "gold",
            delta = bd("-0.0001"),
            allowNegative = true
        )

        assertFalse(earned.success)
        assertFalse(spent.success)
        assertTrue(earned.committedBalances.isEmpty())
        assertTrue(spent.committedBalances.isEmpty())
        assertEquals(BigDecimal.ZERO, storage.getBalance(earnedPlayer, "gold")?.balance)
        assertEquals(BigDecimal.ZERO, storage.getBalance(spentPlayer, "gold")?.balance)
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

        val committed = storage.transferBalances(request)
        assertTrue(committed.success)
        assertEquals(
            BalanceData(
                balance = bd("70"),
                totalEarned = BigDecimal.ZERO,
                totalSpent = bd("30")
            ),
            committed.getCommittedBalance(from, "gold")
        )
        assertEquals(
            BalanceData(
                balance = bd("35"),
                totalEarned = bd("25"),
                totalSpent = BigDecimal.ZERO
            ),
            committed.getCommittedBalance(to, "gold")
        )
        assertEquals(bd("70"), storage.getBalance(from, "gold")?.balance)
        assertEquals(bd("35"), storage.getBalance(to, "gold")?.balance)

        val failed = storage.transferBalances(request.copy(recipientMaxBalance = bd("50")))
        assertFalse(failed.success)
        assertEquals(AtomicBalanceFailure.BALANCE_LIMIT, failed.failure)
        assertTrue(failed.committedBalances.isEmpty())
        assertEquals(bd("70"), storage.getBalance(from, "gold")?.balance)
        assertEquals(bd("35"), storage.getBalance(to, "gold")?.balance)
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

        val committed = storage.exchangeBalances(request)
        assertTrue(committed.success)
        assertEquals(bd("90"), committed.getCommittedBalance(player, "gold")?.balance)
        assertEquals(bd("3"), committed.getCommittedBalance(player, "points")?.balance)
        val failed = storage.exchangeBalances(request)
        assertFalse(failed.success)
        assertEquals(AtomicBalanceFailure.PERIOD_LIMIT, failed.failure)
        assertTrue(failed.committedBalances.isEmpty())
        assertEquals(bd("90"), storage.getBalance(player, "gold")?.balance)
        assertEquals(bd("3"), storage.getBalance(player, "points")?.balance)
    }

    @Test
    fun `exchange keeps only the current quota window`() {
        val storage = createProvider()
        val player = UUID.randomUUID()
        val request = ExchangeBalanceRequest(
            playerUuid = player,
            ruleId = "daily",
            fromCurrencyId = "gold",
            toCurrencyId = "points",
            debitAmount = bd("1"),
            creditAmount = bd("1"),
            allowNegative = false,
            fromInitialBalance = bd("10"),
            toInitialBalance = BigDecimal.ZERO,
            toMaxBalance = bd("100"),
            periodStart = 1_000L,
            periodLimit = bd("100")
        )

        assertTrue(storage.exchangeBalances(request).success)
        assertTrue(storage.exchangeBalances(request.copy(periodStart = 2_000L)).success)

        withRawConnection { conn ->
            conn.prepareStatement("""
                SELECT COUNT(*) AS total, MIN(period_start) AS oldest
                FROM rondo_exchange_quota
                WHERE player_uuid = ? AND rule_id = ?
            """.trimIndent()).use { ps ->
                ps.setString(1, player.toString())
                ps.setString(2, request.ruleId)
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(1, rs.getInt("total"))
                    assertEquals(2_000L, rs.getLong("oldest"))
                }
            }
        }
    }

    @Test
    fun `retention cleanup covers transaction and exchange audit records`() {
        val storage = createProvider()
        val player = UUID.randomUUID()
        withRawConnection { conn ->
            conn.prepareStatement("""
                INSERT INTO rondo_log
                    (player_uuid, currency_id, action, amount, balance, source, created_at)
                VALUES (?, 'gold', 'DEPOSIT', '1', '1', 'test', '2000-01-01 00:00:00')
            """.trimIndent()).use { ps ->
                ps.setString(1, player.toString())
                ps.executeUpdate()
            }
            conn.prepareStatement("""
                INSERT INTO rondo_exchange_record
                    (player_uuid, rule_id, amount, created_at)
                VALUES (?, 'daily', '1', '2000-01-01 00:00:00')
            """.trimIndent()).use { ps ->
                ps.setString(1, player.toString())
                ps.executeUpdate()
            }
        }

        storage.cleanExpiredData(30, 0L)

        withRawConnection { conn ->
            assertEquals(0, countRows(conn, "rondo_log"))
            assertEquals(0, countRows(conn, "rondo_exchange_record"))
        }
    }

    @Test
    fun `sixty day growth is cleaned in batches while permanent audit retention keeps logs`() {
        val storage = createProvider()
        val players = List(20) { UUID.randomUUID() }
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneOffset.UTC)
        val simulatedDays = (0..29) + (31..60)

        withRawConnection { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement("""
                    INSERT INTO rondo_log
                        (player_uuid, currency_id, action, amount, balance, source, created_at)
                    VALUES (?, 'gold', 'DEPOSIT', '1', '1', ?, ?)
                """.trimIndent()).use { log ->
                    conn.prepareStatement("""
                        INSERT INTO rondo_exchange_record
                            (player_uuid, rule_id, amount, created_at)
                        VALUES (?, 'daily', '1', ?)
                    """.trimIndent()).use { exchange ->
                        conn.prepareStatement("""
                            INSERT INTO rondo_exchange_quota
                                (player_uuid, rule_id, period_start, amount)
                            VALUES (?, 'daily', ?, '10')
                        """.trimIndent()).use { quota ->
                            for (day in simulatedDays) {
                                val instant = now.minus(day.toLong(), ChronoUnit.DAYS)
                                val timestamp = formatter.format(instant)
                                for (player in players) {
                                    repeat(10) { operation ->
                                        log.setString(1, player.toString())
                                        log.setString(2, "qa:$day:$operation")
                                        log.setString(3, timestamp)
                                        log.addBatch()

                                        exchange.setString(1, player.toString())
                                        exchange.setString(2, timestamp)
                                        exchange.addBatch()
                                    }
                                    quota.setString(1, player.toString())
                                    quota.setLong(2, instant.toEpochMilli())
                                    quota.addBatch()
                                }
                            }
                            log.executeBatch()
                            exchange.executeBatch()
                            quota.executeBatch()
                        }
                    }
                }
                conn.commit()
            } catch (failure: Throwable) {
                conn.rollback()
                throw failure
            } finally {
                conn.autoCommit = true
            }
        }

        val quotaCutoff = now.minus(30, ChronoUnit.DAYS).toEpochMilli()
        storage.cleanExpiredData(-1, quotaCutoff)
        withRawConnection { conn ->
            assertEquals(12_000, countRows(conn, "rondo_log"))
            assertEquals(12_000, countRows(conn, "rondo_exchange_record"))
            assertEquals(600, countRows(conn, "rondo_exchange_quota"))
        }

        storage.cleanExpiredData(30, quotaCutoff)
        withRawConnection { conn ->
            assertEquals(6_000, countRows(conn, "rondo_log"))
            assertEquals(6_000, countRows(conn, "rondo_exchange_record"))
            assertEquals(600, countRows(conn, "rondo_exchange_quota"))
        }
    }

    @Test
    fun `logs with the same timestamp use newest id as stable tie breaker`() {
        val storage = createProvider()
        val player = UUID.randomUUID()
        val timestamp = 1_700_000_000_000L
        storage.insertLog(
            TransactionLog(
                playerUuid = player,
                currencyId = "gold",
                action = TransactionLog.Action.DEPOSIT,
                amount = BigDecimal.ONE,
                balanceAfter = BigDecimal.ONE,
                source = "first",
                timestamp = timestamp
            )
        )
        storage.insertLog(
            TransactionLog(
                playerUuid = player,
                currencyId = "gold",
                action = TransactionLog.Action.DEPOSIT,
                amount = BigDecimal.ONE,
                balanceAfter = bd("2"),
                source = "second",
                timestamp = timestamp
            )
        )

        assertEquals(
            listOf("second", "first"),
            storage.queryLogs(player, "gold", 1, 10).map { it.source }
        )
    }

    @Test
    fun `extreme log page does not overflow into the first page`() {
        val storage = createProvider()
        val player = UUID.randomUUID()
        storage.insertLog(
            TransactionLog(
                playerUuid = player,
                currencyId = "gold",
                action = TransactionLog.Action.DEPOSIT,
                amount = BigDecimal.ONE,
                balanceAfter = BigDecimal.ONE,
                source = "test"
            )
        )

        assertTrue(storage.queryLogs(player, null, Int.MAX_VALUE, 100).isEmpty())
    }

    @Test
    fun `ranking keeps decimal ordering beyond double integer precision`() {
        val storage = createProvider()
        val lower = UUID.randomUUID()
        val higher = UUID.randomUUID()
        storage.setBalance(lower, "gold", bd("9007199254740992.0001"))
        storage.setBalance(higher, "gold", bd("9007199254740992.0002"))

        val ranking = storage.queryRanking("gold", 2)
        assertEquals(higher, ranking[0].playerUuid)
        assertEquals(lower, ranking[1].playerUuid)
    }

    @Test
    fun `player rank matches ranking tie breaker and missing players are unranked`() {
        val storage = createProvider()
        val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val second = UUID.fromString("00000000-0000-0000-0000-000000000002")
        storage.setBalance(second, "gold", bd("10"))
        storage.setBalance(first, "gold", bd("10"))

        assertEquals(1, storage.queryPlayerRank(first, "gold"))
        assertEquals(2, storage.queryPlayerRank(second, "gold"))
        assertNull(storage.queryPlayerRank(UUID.randomUUID(), "gold"))
    }

    @Test
    fun `concurrent withdrawals never overdraw`() {
        val storage = createProvider()
        val player = UUID.randomUUID()
        storage.setBalance(player, "gold", bd("10"))
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = executor.invokeAll(
                List(100) {
                    Callable {
                        storage.updateBalance(
                            playerUuid = player,
                            currencyId = "gold",
                            delta = BigDecimal.ONE.negate(),
                            allowNegative = false
                        ).success
                    }
                }
            )
            assertEquals(10, results.count { it.get() })
            assertEquals(BigDecimal.ZERO, storage.getBalance(player, "gold")?.balance)
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

    private fun withRawConnection(block: (java.sql.Connection) -> Unit) {
        DriverManager.getConnection(
            "jdbc:sqlite:${tempDir.resolve("rondo-test.db").toAbsolutePath()}"
        ).use(block)
    }

    private fun countRows(conn: java.sql.Connection, table: String): Int {
        conn.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $table").use { rs ->
                assertTrue(rs.next())
                return rs.getInt(1)
            }
        }
    }

    private fun bd(value: String): BigDecimal = BigDecimal(value)
}
