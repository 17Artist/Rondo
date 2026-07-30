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

import priv.seventeen.artist.blink.BlinkLog
import priv.seventeen.artist.blink.bukkitPlugin
import priv.seventeen.artist.rondo.log.TransactionLog
import priv.seventeen.artist.rondo.currency.MoneyConstraints
import java.io.File
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * SQLite 存储实现
 */
class SQLiteProvider(
    private val configuredDatabaseFile: File? = null,
    private val logInitialization: Boolean = true
) : StorageProvider {

    private lateinit var connection: Connection
    private lateinit var databaseFile: File
    private val lock = Any()

    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    override fun initialize() {
        databaseFile = configuredDatabaseFile ?: File(bukkitPlugin.dataFolder, "data.db")
        val parent = databaseFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            error("无法创建 SQLite 数据目录: ${parent.absolutePath}")
        }
        connection = DriverManager.getConnection("jdbc:sqlite:${databaseFile.absolutePath}")
        connection.createStatement().use { stmt ->
            stmt.executeUpdate("PRAGMA journal_mode=WAL")
            stmt.executeUpdate("PRAGMA synchronous=NORMAL")
            stmt.executeUpdate("PRAGMA busy_timeout=5000")
            stmt.executeUpdate("PRAGMA foreign_keys=ON")
        }
        createTables()
        if (logInitialization) {
            BlinkLog.info("SQLite storage initialized.")
        }
    }

    override fun shutdown() {
        synchronized(lock) {
            if (::connection.isInitialized && !connection.isClosed) {
                connection.close()
            }
        }
    }

    private fun getConnection(): Connection {
        synchronized(lock) {
            if (connection.isClosed) {
                connection = DriverManager.getConnection("jdbc:sqlite:${databaseFile.absolutePath}")
            }
            return connection
        }
    }

    private fun createTables() {
        synchronized(lock) {
            getConnection().createStatement().use { stmt ->
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS rondo_balance (
                        player_uuid  TEXT    NOT NULL,
                        currency_id  TEXT    NOT NULL,
                        balance      TEXT    NOT NULL DEFAULT '0',
                        total_earned TEXT    NOT NULL DEFAULT '0',
                        total_spent  TEXT    NOT NULL DEFAULT '0',
                        updated_at   TEXT    DEFAULT (datetime('now')),
                        PRIMARY KEY (player_uuid, currency_id)
                    )
                """.trimIndent())

                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS rondo_log (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_uuid TEXT    NOT NULL,
                        currency_id TEXT    NOT NULL,
                        action      TEXT    NOT NULL,
                        amount      TEXT    NOT NULL,
                        balance     TEXT    NOT NULL,
                        source      TEXT    NOT NULL,
                        detail      TEXT,
                        created_at  TEXT    DEFAULT (datetime('now'))
                    )
                """.trimIndent())

                stmt.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_log_player_time ON rondo_log (player_uuid, created_at)
                """.trimIndent())

                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS rondo_exchange_record (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_uuid TEXT    NOT NULL,
                        rule_id     TEXT    NOT NULL,
                        amount      TEXT    NOT NULL,
                        created_at  TEXT    DEFAULT (datetime('now'))
                    )
                """.trimIndent())

                stmt.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_exchange_player_rule ON rondo_exchange_record (player_uuid, rule_id, created_at)
                """.trimIndent())

                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS rondo_exchange_quota (
                        player_uuid TEXT NOT NULL,
                        rule_id     TEXT NOT NULL,
                        period_start INTEGER NOT NULL,
                        amount      TEXT NOT NULL DEFAULT '0',
                        PRIMARY KEY (player_uuid, rule_id, period_start)
                    )
                """.trimIndent())

                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS rondo_player (
                        player_uuid TEXT PRIMARY KEY,
                        player_name TEXT NOT NULL,
                        updated_at  TEXT DEFAULT (datetime('now'))
                    )
                """.trimIndent())
            }
        }
    }

    override fun loadBalances(playerUuid: UUID): Map<String, BalanceData> {
        val result = mutableMapOf<String, BalanceData>()
        synchronized(lock) {
            getConnection().prepareStatement("SELECT currency_id, balance, total_earned, total_spent FROM rondo_balance WHERE player_uuid = ?").use { ps ->
                ps.setString(1, playerUuid.toString())
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        result[rs.getString("currency_id")] = BalanceData(
                            balance = BigDecimal(rs.getString("balance") ?: "0"),
                            totalEarned = BigDecimal(rs.getString("total_earned") ?: "0"),
                            totalSpent = BigDecimal(rs.getString("total_spent") ?: "0")
                        )
                    }
                }
            }
        }
        return result
    }

    override fun updateBalance(
        playerUuid: UUID,
        currencyId: String,
        delta: BigDecimal,
        allowNegative: Boolean,
        maxBalance: BigDecimal?,
        initialBalance: BigDecimal
    ): AtomicBalanceResult {
        synchronized(lock) {
            val conn = getConnection()
            conn.autoCommit = false
            try {
                // 确保记录存在
                conn.prepareStatement("""
                    INSERT OR IGNORE INTO rondo_balance (player_uuid, currency_id, balance, total_earned, total_spent)
                    VALUES (?, ?, ?, '0', '0')
                """.trimIndent()).use { ps ->
                    ps.setString(1, playerUuid.toString())
                    ps.setString(2, currencyId)
                    ps.setString(3, initialBalance.toPlainString())
                    ps.executeUpdate()
                }

                // 读取当前余额
                var current = BigDecimal.ZERO
                var earned = BigDecimal.ZERO
                var spent = BigDecimal.ZERO
                conn.prepareStatement("SELECT balance, total_earned, total_spent FROM rondo_balance WHERE player_uuid = ? AND currency_id = ?").use { ps ->
                    ps.setString(1, playerUuid.toString())
                    ps.setString(2, currencyId)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            current = BigDecimal(rs.getString("balance") ?: "0")
                            earned = BigDecimal(rs.getString("total_earned") ?: "0")
                            spent = BigDecimal(rs.getString("total_spent") ?: "0")
                        }
                    }
                }

                // 检查余额是否足够（扣款时）
                val newBalance = current.add(delta)
                if (!MoneyConstraints.isStorable(newBalance) ||
                    (delta < BigDecimal.ZERO && newBalance < BigDecimal.ZERO && !allowNegative)
                ) {
                    conn.rollback()
                    return AtomicBalanceResult(
                        false,
                        AtomicBalanceFailure.INSUFFICIENT_FUNDS
                    )
                }
                // 检查余额上限（存入时）
                if (delta > BigDecimal.ZERO && maxBalance != null && newBalance > maxBalance) {
                    conn.rollback()
                    return AtomicBalanceResult(false, AtomicBalanceFailure.BALANCE_LIMIT)
                }

                // 更新余额
                val newEarned = if (delta >= BigDecimal.ZERO) earned.add(delta) else earned
                val newSpent = if (delta < BigDecimal.ZERO) spent.add(delta.abs()) else spent
                if (!MoneyConstraints.isCumulativeStorable(newEarned) ||
                    !MoneyConstraints.isCumulativeStorable(newSpent)
                ) {
                    conn.rollback()
                    return AtomicBalanceResult(false, AtomicBalanceFailure.BALANCE_LIMIT)
                }

                conn.prepareStatement("""
                    UPDATE rondo_balance SET balance = ?, total_earned = ?, total_spent = ?, updated_at = datetime('now')
                    WHERE player_uuid = ? AND currency_id = ?
                """.trimIndent()).use { ps ->
                    ps.setString(1, newBalance.toPlainString())
                    ps.setString(2, newEarned.toPlainString())
                    ps.setString(3, newSpent.toPlainString())
                    ps.setString(4, playerUuid.toString())
                    ps.setString(5, currencyId)
                    ps.executeUpdate()
                }
                val committed = BalanceData(newBalance, newEarned, newSpent)
                conn.commit()
                return AtomicBalanceResult(
                    success = true,
                    committedBalances = listOf(
                        CommittedBalance(playerUuid, currencyId, committed)
                    )
                )
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun setBalance(
        playerUuid: UUID,
        currencyId: String,
        amount: BigDecimal
    ): AtomicBalanceResult {
        synchronized(lock) {
            val conn = getConnection()
            conn.autoCommit = false
            try {
                conn.prepareStatement("""
                    INSERT INTO rondo_balance
                        (player_uuid, currency_id, balance, total_earned, total_spent, updated_at)
                    VALUES (?, ?, ?, '0', '0', datetime('now'))
                    ON CONFLICT(player_uuid, currency_id) DO UPDATE
                    SET balance = excluded.balance, updated_at = datetime('now')
                """.trimIndent()).use { ps ->
                    ps.setString(1, playerUuid.toString())
                    ps.setString(2, currencyId)
                    ps.setString(3, amount.toPlainString())
                    ps.executeUpdate()
                }
                val committed = readBalance(conn, playerUuid, currencyId)
                conn.commit()
                return AtomicBalanceResult(
                    success = true,
                    committedBalances = listOf(
                        CommittedBalance(playerUuid, currencyId, committed)
                    )
                )
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun transferBalances(request: TransferBalanceRequest): AtomicBalanceResult {
        require(request.from != request.to) { "转账双方不能相同" }
        synchronized(lock) {
            val conn = getConnection()
            conn.autoCommit = false
            try {
                ensureBalanceRow(conn, request.from, request.currencyId, request.senderInitialBalance)
                ensureBalanceRow(conn, request.to, request.currencyId, request.recipientInitialBalance)

                val fromData = readBalance(conn, request.from, request.currencyId)
                val toData = readBalance(conn, request.to, request.currencyId)
                val fromAfter = fromData.balance.subtract(request.debitAmount)
                val toAfter = toData.balance.add(request.creditAmount)

                if (!MoneyConstraints.isStorable(fromAfter) ||
                    (!request.allowNegative && fromAfter < BigDecimal.ZERO)
                ) {
                    conn.rollback()
                    return AtomicBalanceResult(false, AtomicBalanceFailure.INSUFFICIENT_FUNDS)
                }
                if (!MoneyConstraints.isStorable(toAfter) ||
                    (request.recipientMaxBalance != null && toAfter > request.recipientMaxBalance)
                ) {
                    conn.rollback()
                    return AtomicBalanceResult(false, AtomicBalanceFailure.BALANCE_LIMIT)
                }
                if (!MoneyConstraints.isCumulativeStorable(
                        fromData.totalSpent.add(request.debitAmount)
                    ) ||
                    !MoneyConstraints.isCumulativeStorable(
                        toData.totalEarned.add(request.creditAmount)
                    )
                ) {
                    conn.rollback()
                    return AtomicBalanceResult(false, AtomicBalanceFailure.BALANCE_LIMIT)
                }

                val committedFrom = fromData.copy(
                    balance = fromAfter,
                    totalSpent = fromData.totalSpent.add(request.debitAmount)
                )
                val committedTo = toData.copy(
                    balance = toAfter,
                    totalEarned = toData.totalEarned.add(request.creditAmount)
                )
                writeBalance(conn, request.from, request.currencyId, committedFrom)
                writeBalance(conn, request.to, request.currencyId, committedTo)
                conn.commit()
                return AtomicBalanceResult(
                    success = true,
                    committedBalances = listOf(
                        CommittedBalance(request.from, request.currencyId, committedFrom),
                        CommittedBalance(request.to, request.currencyId, committedTo)
                    )
                )
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun exchangeBalances(request: ExchangeBalanceRequest): AtomicBalanceResult {
        require(request.fromCurrencyId != request.toCurrencyId) { "兑换货币不能相同" }
        synchronized(lock) {
            val conn = getConnection()
            conn.autoCommit = false
            try {
                ensureBalanceRow(conn, request.playerUuid, request.fromCurrencyId, request.fromInitialBalance)
                ensureBalanceRow(conn, request.playerUuid, request.toCurrencyId, request.toInitialBalance)

                val fromData = readBalance(conn, request.playerUuid, request.fromCurrencyId)
                val toData = readBalance(conn, request.playerUuid, request.toCurrencyId)
                val fromAfter = fromData.balance.subtract(request.debitAmount)
                val toAfter = toData.balance.add(request.creditAmount)

                if (!MoneyConstraints.isStorable(fromAfter) ||
                    (!request.allowNegative && fromAfter < BigDecimal.ZERO)
                ) {
                    conn.rollback()
                    return AtomicBalanceResult(false, AtomicBalanceFailure.INSUFFICIENT_FUNDS)
                }
                if (!MoneyConstraints.isStorable(toAfter) ||
                    (request.toMaxBalance != null && toAfter > request.toMaxBalance)
                ) {
                    conn.rollback()
                    return AtomicBalanceResult(false, AtomicBalanceFailure.BALANCE_LIMIT)
                }
                if (!MoneyConstraints.isCumulativeStorable(
                        fromData.totalSpent.add(request.debitAmount)
                    ) ||
                    !MoneyConstraints.isCumulativeStorable(
                        toData.totalEarned.add(request.creditAmount)
                    )
                ) {
                    conn.rollback()
                    return AtomicBalanceResult(false, AtomicBalanceFailure.BALANCE_LIMIT)
                }

                if (request.periodStart != null && request.periodLimit != null) {
                    val used = lockAndReadExchangeQuota(conn, request)
                    if (used.add(request.creditAmount) > request.periodLimit) {
                        conn.rollback()
                        return AtomicBalanceResult(false, AtomicBalanceFailure.PERIOD_LIMIT)
                    }
                    conn.prepareStatement("""
                        UPDATE rondo_exchange_quota SET amount = ?
                        WHERE player_uuid = ? AND rule_id = ? AND period_start = ?
                    """.trimIndent()).use { ps ->
                        ps.setString(1, used.add(request.creditAmount).toPlainString())
                        ps.setString(2, request.playerUuid.toString())
                        ps.setString(3, request.ruleId)
                        ps.setLong(4, request.periodStart)
                        ps.executeUpdate()
                    }
                }

                val committedFrom = fromData.copy(
                    balance = fromAfter,
                    totalSpent = fromData.totalSpent.add(request.debitAmount)
                )
                val committedTo = toData.copy(
                    balance = toAfter,
                    totalEarned = toData.totalEarned.add(request.creditAmount)
                )
                writeBalance(conn, request.playerUuid, request.fromCurrencyId, committedFrom)
                writeBalance(conn, request.playerUuid, request.toCurrencyId, committedTo)
                conn.prepareStatement("""
                    INSERT INTO rondo_exchange_record (player_uuid, rule_id, amount, created_at)
                    VALUES (?, ?, ?, datetime('now'))
                """.trimIndent()).use { ps ->
                    ps.setString(1, request.playerUuid.toString())
                    ps.setString(2, request.ruleId)
                    ps.setString(3, request.creditAmount.toPlainString())
                    ps.executeUpdate()
                }
                conn.commit()
                return AtomicBalanceResult(
                    success = true,
                    committedBalances = listOf(
                        CommittedBalance(
                            request.playerUuid,
                            request.fromCurrencyId,
                            committedFrom
                        ),
                        CommittedBalance(
                            request.playerUuid,
                            request.toCurrencyId,
                            committedTo
                        )
                    )
                )
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    private fun ensureBalanceRow(
        conn: Connection,
        playerUuid: UUID,
        currencyId: String,
        initialBalance: BigDecimal
    ) {
        conn.prepareStatement("""
            INSERT OR IGNORE INTO rondo_balance
                (player_uuid, currency_id, balance, total_earned, total_spent)
            VALUES (?, ?, ?, '0', '0')
        """.trimIndent()).use { ps ->
            ps.setString(1, playerUuid.toString())
            ps.setString(2, currencyId)
            ps.setString(3, initialBalance.toPlainString())
            ps.executeUpdate()
        }
    }

    private fun readBalance(conn: Connection, playerUuid: UUID, currencyId: String): BalanceData {
        conn.prepareStatement("""
            SELECT balance, total_earned, total_spent
            FROM rondo_balance WHERE player_uuid = ? AND currency_id = ?
        """.trimIndent()).use { ps ->
            ps.setString(1, playerUuid.toString())
            ps.setString(2, currencyId)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "余额记录初始化失败: $playerUuid/$currencyId" }
                return BalanceData(
                    balance = BigDecimal(rs.getString("balance")),
                    totalEarned = BigDecimal(rs.getString("total_earned")),
                    totalSpent = BigDecimal(rs.getString("total_spent"))
                )
            }
        }
    }

    private fun writeBalance(
        conn: Connection,
        playerUuid: UUID,
        currencyId: String,
        data: BalanceData
    ) {
        requireValidBalanceData(data)
        conn.prepareStatement("""
            UPDATE rondo_balance
            SET balance = ?, total_earned = ?, total_spent = ?, updated_at = datetime('now')
            WHERE player_uuid = ? AND currency_id = ?
        """.trimIndent()).use { ps ->
            ps.setString(1, data.balance.toPlainString())
            ps.setString(2, data.totalEarned.toPlainString())
            ps.setString(3, data.totalSpent.toPlainString())
            ps.setString(4, playerUuid.toString())
            ps.setString(5, currencyId)
            check(ps.executeUpdate() == 1) { "余额记录更新失败: $playerUuid/$currencyId" }
        }
    }

    private fun requireValidBalanceData(data: BalanceData) {
        require(MoneyConstraints.isStorable(data.balance)) {
            "余额超出 DECIMAL(20,4) 可存储范围"
        }
        require(MoneyConstraints.isCumulativeStorable(data.totalEarned)) {
            "累计获得超出 DECIMAL(38,4) 兼容范围"
        }
        require(MoneyConstraints.isCumulativeStorable(data.totalSpent)) {
            "累计消耗超出 DECIMAL(38,4) 兼容范围"
        }
    }

    private fun lockAndReadExchangeQuota(
        conn: Connection,
        request: ExchangeBalanceRequest
    ): BigDecimal {
        conn.prepareStatement("""
            INSERT OR IGNORE INTO rondo_exchange_quota
                (player_uuid, rule_id, period_start, amount)
            VALUES (?, ?, ?, '0')
        """.trimIndent()).use { ps ->
            ps.setString(1, request.playerUuid.toString())
            ps.setString(2, request.ruleId)
            ps.setLong(3, request.periodStart!!)
            ps.executeUpdate()
        }

        conn.prepareStatement("""
            DELETE FROM rondo_exchange_quota
            WHERE player_uuid = ? AND rule_id = ? AND period_start < ?
        """.trimIndent()).use { ps ->
            ps.setString(1, request.playerUuid.toString())
            ps.setString(2, request.ruleId)
            ps.setLong(3, request.periodStart!!)
            ps.executeUpdate()
        }

        conn.prepareStatement("""
            SELECT amount FROM rondo_exchange_quota
            WHERE player_uuid = ? AND rule_id = ? AND period_start = ?
        """.trimIndent()).use { ps ->
            ps.setString(1, request.playerUuid.toString())
            ps.setString(2, request.ruleId)
            ps.setLong(3, request.periodStart!!)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "兑换额度记录初始化失败" }
                return BigDecimal(rs.getString("amount"))
            }
        }
    }

    override fun savePlayerName(playerUuid: UUID, playerName: String) {
        synchronized(lock) {
            getConnection().prepareStatement("""
                INSERT INTO rondo_player (player_uuid, player_name, updated_at)
                VALUES (?, ?, datetime('now'))
                ON CONFLICT(player_uuid) DO UPDATE
                SET player_name = excluded.player_name, updated_at = datetime('now')
            """.trimIndent()).use { ps ->
                ps.setString(1, playerUuid.toString())
                ps.setString(2, playerName)
                ps.executeUpdate()
            }
        }
    }

    override fun getBalance(playerUuid: UUID, currencyId: String): BalanceData? {
        synchronized(lock) {
            getConnection().prepareStatement("SELECT balance, total_earned, total_spent FROM rondo_balance WHERE player_uuid = ? AND currency_id = ?").use { ps ->
                ps.setString(1, playerUuid.toString())
                ps.setString(2, currencyId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        return BalanceData(
                            balance = BigDecimal(rs.getString("balance") ?: "0"),
                            totalEarned = BigDecimal(rs.getString("total_earned") ?: "0"),
                            totalSpent = BigDecimal(rs.getString("total_spent") ?: "0")
                        )
                    }
                }
            }
        }
        return null
    }

    override fun insertLog(log: TransactionLog) {
        synchronized(lock) {
            getConnection().prepareStatement("""
                INSERT INTO rondo_log (player_uuid, currency_id, action, amount, balance, source, detail, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()).use { ps ->
                ps.setString(1, log.playerUuid.toString())
                ps.setString(2, log.currencyId)
                ps.setString(3, log.action.name)
                ps.setString(4, log.amount.toPlainString())
                ps.setString(5, log.balanceAfter.toPlainString())
                ps.setString(6, log.source)
                ps.setString(7, log.detail)
                ps.setString(8, formatTimestamp(log.timestamp))
                ps.executeUpdate()
            }
        }
    }

    override fun insertLogsBatch(logs: List<TransactionLog>) {
        if (logs.isEmpty()) return
        synchronized(lock) {
            val conn = getConnection()
            conn.autoCommit = false
            try {
                conn.prepareStatement("""
                    INSERT INTO rondo_log (player_uuid, currency_id, action, amount, balance, source, detail, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()).use { ps ->
                    for (log in logs) {
                        ps.setString(1, log.playerUuid.toString())
                        ps.setString(2, log.currencyId)
                        ps.setString(3, log.action.name)
                        ps.setString(4, log.amount.toPlainString())
                        ps.setString(5, log.balanceAfter.toPlainString())
                        ps.setString(6, log.source)
                        ps.setString(7, log.detail)
                        ps.setString(8, formatTimestamp(log.timestamp))
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun queryLogs(playerUuid: UUID, currencyId: String?, page: Int, pageSize: Int): List<TransactionLog> {
        val result = mutableListOf<TransactionLog>()
        val offset = (page.toLong() - 1L) * pageSize.toLong()
        val sql = if (currencyId != null) {
            "SELECT * FROM rondo_log WHERE player_uuid = ? AND currency_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?"
        } else {
            "SELECT * FROM rondo_log WHERE player_uuid = ? ORDER BY created_at DESC LIMIT ? OFFSET ?"
        }
        synchronized(lock) {
            getConnection().prepareStatement(sql).use { ps ->
                var idx = 1
                ps.setString(idx++, playerUuid.toString())
                if (currencyId != null) ps.setString(idx++, currencyId)
                ps.setInt(idx++, pageSize)
                ps.setLong(idx, offset)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val timeStr = rs.getString("created_at")
                        val time = parseTimestamp(timeStr)
                        result.add(TransactionLog(
                            playerUuid = UUID.fromString(rs.getString("player_uuid")),
                            currencyId = rs.getString("currency_id"),
                            action = TransactionLog.Action.valueOf(rs.getString("action")),
                            amount = BigDecimal(rs.getString("amount") ?: "0"),
                            balanceAfter = BigDecimal(rs.getString("balance") ?: "0"),
                            source = rs.getString("source"),
                            detail = rs.getString("detail"),
                            timestamp = time
                        ))
                    }
                }
            }
        }
        return result
    }

    override fun cleanExpiredLogs(retentionDays: Int) {
        if (retentionDays <= 0) return
        synchronized(lock) {
            val conn = getConnection()
            val modifier = "-$retentionDays days"
            val deletedLogs = conn.prepareStatement(
                "DELETE FROM rondo_log WHERE created_at < datetime('now', ?)"
            ).use { ps ->
                ps.setString(1, modifier)
                ps.executeUpdate()
            }
            val deletedExchanges = conn.prepareStatement(
                "DELETE FROM rondo_exchange_record WHERE created_at < datetime('now', ?)"
            ).use { ps ->
                ps.setString(1, modifier)
                ps.executeUpdate()
            }
            if (logInitialization && (deletedLogs > 0 || deletedExchanges > 0)) {
                BlinkLog.info(
                    "Cleaned $deletedLogs expired transaction logs and " +
                        "$deletedExchanges exchange audit records."
                )
            }
        }
    }

    override fun queryRanking(currencyId: String, limit: Int): List<RankingData> {
        val result = mutableListOf<RankingData>()
        synchronized(lock) {
            getConnection().prepareStatement("""
                SELECT b.player_uuid, b.balance, p.player_name
                FROM rondo_balance b
                LEFT JOIN rondo_player p ON p.player_uuid = b.player_uuid
                WHERE b.currency_id = ?
            """.trimIndent()).use { ps ->
                ps.setString(1, currencyId)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        result.add(RankingData(
                            playerUuid = UUID.fromString(rs.getString("player_uuid")),
                            playerName = rs.getString("player_name"),
                            balance = BigDecimal(rs.getString("balance") ?: "0")
                        ))
                    }
                }
            }
        }
        return result
            .sortedWith(compareByDescending<RankingData> { it.balance }.thenBy { it.playerUuid })
            .take(limit.coerceAtLeast(0))
    }

    override fun queryPlayerRank(playerUuid: UUID, currencyId: String): Int? {
        val all = queryRanking(currencyId, Int.MAX_VALUE)
        val index = all.indexOfFirst { it.playerUuid == playerUuid }
        return if (index >= 0) index + 1 else null
    }

    private fun formatTimestamp(timestamp: Long): String {
        return timestampFormatter.format(Instant.ofEpochMilli(timestamp).atOffset(ZoneOffset.UTC))
    }

    private fun parseTimestamp(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return try {
            LocalDateTime.parse(value, timestampFormatter).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }
}
