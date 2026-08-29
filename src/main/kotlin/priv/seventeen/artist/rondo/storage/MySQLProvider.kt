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

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import priv.seventeen.artist.blink.BlinkLog
import priv.seventeen.artist.rondo.config.MainConfig
import priv.seventeen.artist.rondo.log.TransactionLog
import priv.seventeen.artist.rondo.currency.MoneyConstraints
import java.math.BigDecimal
import java.sql.Connection
import java.sql.Timestamp
import java.util.UUID

/**
 * MySQL 存储实现
 */
class MySQLProvider(private val config: MainConfig) : StorageProvider {

    private companion object {
        const val CLEANUP_BATCH_SIZE = 5_000
    }

    private lateinit var dataSource: HikariDataSource

    override fun initialize() {
        val mysql = config.storage.mysql
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = buildString {
                append("jdbc:mysql://${mysql.host}:${mysql.port}/${mysql.database}")
                append("?sslMode=${mysql.sslMode.uppercase()}")
                append("&allowPublicKeyRetrieval=${mysql.allowPublicKeyRetrieval}")
                append("&characterEncoding=UTF-8&useUnicode=true")
                append("&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true")
            }
            username = mysql.username
            password = mysql.password
            maximumPoolSize = mysql.poolSize
            minimumIdle = minOf(2, mysql.poolSize)
            connectionTimeout = 10000
            idleTimeout = 600000
            maxLifetime = 1800000
            keepaliveTime = 120000
            poolName = "Rondo-HikariPool"
            addDataSourceProperty("cachePrepStmts", "true")
            addDataSourceProperty("prepStmtCacheSize", "250")
            addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
        }
        dataSource = HikariDataSource(hikariConfig)
        createTables()
        BlinkLog.info("MySQL storage initialized.")
    }

    override fun shutdown() {
        if (::dataSource.isInitialized) {
            dataSource.close()
        }
    }

    private fun getConnection(): Connection = dataSource.connection

    private fun createTables() {
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS rondo_balance (
                        player_uuid  CHAR(36)       NOT NULL,
                        currency_id  VARCHAR(64)    NOT NULL,
                        balance      DECIMAL(20,4)  NOT NULL DEFAULT 0,
                        total_earned DECIMAL(38,4)  NOT NULL DEFAULT 0,
                        total_spent  DECIMAL(38,4)  NOT NULL DEFAULT 0,
                        updated_at   TIMESTAMP      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        PRIMARY KEY (player_uuid, currency_id),
                        INDEX idx_currency_balance (currency_id, balance DESC, player_uuid)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent())

                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS rondo_log (
                        id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                        player_uuid CHAR(36)       NOT NULL,
                        currency_id VARCHAR(64)    NOT NULL,
                        action      VARCHAR(16)    NOT NULL,
                        amount      DECIMAL(20,4)  NOT NULL,
                        balance     DECIMAL(20,4)  NOT NULL,
                        source      VARCHAR(128)   NOT NULL,
                        detail      VARCHAR(255),
                        created_at  TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,
                        INDEX idx_player_time (player_uuid, created_at),
                        INDEX idx_log_cleanup (created_at, id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent())

                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS rondo_exchange_record (
                        id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                        player_uuid CHAR(36)       NOT NULL,
                        rule_id     VARCHAR(64)    NOT NULL,
                        amount      DECIMAL(20,4)  NOT NULL,
                        created_at  TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,
                        INDEX idx_player_rule (player_uuid, rule_id, created_at),
                        INDEX idx_exchange_cleanup (created_at, id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent())

                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS rondo_exchange_quota (
                        player_uuid CHAR(36)      NOT NULL,
                        rule_id     VARCHAR(64)   NOT NULL,
                        period_start BIGINT       NOT NULL,
                        amount      DECIMAL(20,4) NOT NULL DEFAULT 0,
                        PRIMARY KEY (player_uuid, rule_id, period_start),
                        INDEX idx_quota_period (period_start)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent())

                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS rondo_player (
                        player_uuid CHAR(36)    NOT NULL PRIMARY KEY,
                        player_name VARCHAR(64) NOT NULL,
                        updated_at  TIMESTAMP   DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent())

                ensureIndex(
                    conn,
                    "rondo_balance",
                    "idx_currency_balance",
                    "currency_id, balance DESC, player_uuid"
                )
                ensureIndex(conn, "rondo_log", "idx_log_cleanup", "created_at, id")
                ensureIndex(
                    conn,
                    "rondo_exchange_record",
                    "idx_exchange_cleanup",
                    "created_at, id"
                )
                ensureIndex(
                    conn,
                    "rondo_exchange_quota",
                    "idx_quota_period",
                    "period_start"
                )
            }
        }
    }

    private fun ensureIndex(
        conn: Connection,
        table: String,
        index: String,
        columns: String
    ) {
        val exists = conn.prepareStatement("""
            SELECT 1
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = ?
              AND index_name = ?
            LIMIT 1
        """.trimIndent()).use { ps ->
            ps.setString(1, table)
            ps.setString(2, index)
            ps.executeQuery().use { it.next() }
        }
        if (!exists) {
            conn.createStatement().use { statement ->
                statement.executeUpdate("ALTER TABLE $table ADD INDEX $index ($columns)")
            }
        }
    }

    override fun loadBalances(playerUuid: UUID): Map<String, BalanceData> {
        val result = mutableMapOf<String, BalanceData>()
        getConnection().use { conn ->
            conn.prepareStatement("SELECT currency_id, balance, total_earned, total_spent FROM rondo_balance WHERE player_uuid = ?").use { ps ->
                ps.setString(1, playerUuid.toString())
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        result[rs.getString("currency_id")] = BalanceData(
                            balance = rs.getBigDecimal("balance"),
                            totalEarned = rs.getBigDecimal("total_earned"),
                            totalSpent = rs.getBigDecimal("total_spent")
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
        getConnection().use { conn ->
            conn.autoCommit = false
            try {
                // 先确保记录存在
                conn.prepareStatement("""
                    INSERT INTO rondo_balance
                        (player_uuid, currency_id, balance, total_earned, total_spent)
                    VALUES (?, ?, ?, 0, 0)
                    ON DUPLICATE KEY UPDATE player_uuid = VALUES(player_uuid)
                """.trimIndent()).use { ps ->
                    ps.setString(1, playerUuid.toString())
                    ps.setString(2, currencyId)
                    ps.setBigDecimal(3, initialBalance)
                    ps.executeUpdate()
                }

                // 更新余额
                val depositWithCap = delta >= BigDecimal.ZERO && maxBalance != null
                val sql = when {
                    // 存入并校验上限：仅当不超过上限时才更新
                    depositWithCap ->
                        "UPDATE rondo_balance SET balance = balance + ?, total_earned = total_earned + ? WHERE player_uuid = ? AND currency_id = ? AND balance + ? <= ? AND total_earned + ? <= ?"
                    delta >= BigDecimal.ZERO ->
                        "UPDATE rondo_balance SET balance = balance + ?, total_earned = total_earned + ? WHERE player_uuid = ? AND currency_id = ? AND total_earned + ? <= ?"
                    allowNegative ->
                        "UPDATE rondo_balance SET balance = balance + ?, total_spent = total_spent + ? WHERE player_uuid = ? AND currency_id = ? AND balance + ? >= ? AND total_spent + ? <= ?"
                    else ->
                        "UPDATE rondo_balance SET balance = balance + ?, total_spent = total_spent + ? WHERE player_uuid = ? AND currency_id = ? AND balance + ? >= 0 AND total_spent + ? <= ?"
                }
                conn.prepareStatement(sql).use { ps ->
                    ps.setBigDecimal(1, delta)
                    ps.setBigDecimal(2, delta.abs())
                    ps.setString(3, playerUuid.toString())
                    ps.setString(4, currencyId)
                    if (depositWithCap) {
                        ps.setBigDecimal(5, delta)
                        ps.setBigDecimal(6, maxBalance)
                        ps.setBigDecimal(7, delta.abs())
                        ps.setBigDecimal(8, MoneyConstraints.MAX_CUMULATIVE_AMOUNT)
                    } else if (delta >= BigDecimal.ZERO) {
                        ps.setBigDecimal(5, delta.abs())
                        ps.setBigDecimal(6, MoneyConstraints.MAX_CUMULATIVE_AMOUNT)
                    } else if (delta < BigDecimal.ZERO && allowNegative) {
                        ps.setBigDecimal(5, delta)
                        ps.setBigDecimal(6, MoneyConstraints.MIN_ABSOLUTE_BALANCE)
                        ps.setBigDecimal(7, delta.abs())
                        ps.setBigDecimal(8, MoneyConstraints.MAX_CUMULATIVE_AMOUNT)
                    } else if (delta < BigDecimal.ZERO) {
                        ps.setBigDecimal(5, delta)
                        ps.setBigDecimal(6, delta.abs())
                        ps.setBigDecimal(7, MoneyConstraints.MAX_CUMULATIVE_AMOUNT)
                    }
                    val rows = ps.executeUpdate()
                    if (rows == 0) {
                        val failure = if (delta < BigDecimal.ZERO) {
                            val current = readBalanceForUpdate(conn, playerUuid, currencyId)
                            if (!MoneyConstraints.isCumulativeStorable(
                                    current.totalSpent.add(delta.abs())
                                )
                            ) {
                                AtomicBalanceFailure.BALANCE_LIMIT
                            } else {
                                AtomicBalanceFailure.INSUFFICIENT_FUNDS
                            }
                        } else {
                            AtomicBalanceFailure.BALANCE_LIMIT
                        }
                        conn.rollback()
                        return AtomicBalanceResult(
                            success = false,
                            failure = failure
                        )
                    }
                }
                val committed = readBalanceForUpdate(conn, playerUuid, currencyId)
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
        getConnection().use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement("""
                    INSERT INTO rondo_balance
                        (player_uuid, currency_id, balance, total_earned, total_spent)
                    VALUES (?, ?, ?, 0, 0)
                    ON DUPLICATE KEY UPDATE balance = VALUES(balance)
                """.trimIndent()).use { ps ->
                    ps.setString(1, playerUuid.toString())
                    ps.setString(2, currencyId)
                    ps.setBigDecimal(3, amount)
                    ps.executeUpdate()
                }
                val committed = readBalanceForUpdate(conn, playerUuid, currencyId)
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
        getConnection().use { conn ->
            conn.autoCommit = false
            try {
                val locked = linkedMapOf<UUID, BalanceData>()
                listOf(request.from, request.to).sortedBy(UUID::toString).forEach { uuid ->
                    val initial = if (uuid == request.from) {
                        request.senderInitialBalance
                    } else {
                        request.recipientInitialBalance
                    }
                    ensureBalanceRow(conn, uuid, request.currencyId, initial)
                    locked[uuid] = readBalanceForUpdate(conn, uuid, request.currencyId)
                }
                val fromData = locked.getValue(request.from)
                val toData = locked.getValue(request.to)
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
        getConnection().use { conn ->
            conn.autoCommit = false
            try {
                val locked = linkedMapOf<String, BalanceData>()
                listOf(request.fromCurrencyId, request.toCurrencyId).sorted().forEach { currencyId ->
                    val initial = if (currencyId == request.fromCurrencyId) {
                        request.fromInitialBalance
                    } else {
                        request.toInitialBalance
                    }
                    ensureBalanceRow(conn, request.playerUuid, currencyId, initial)
                    locked[currencyId] = readBalanceForUpdate(conn, request.playerUuid, currencyId)
                }
                val fromData = locked.getValue(request.fromCurrencyId)
                val toData = locked.getValue(request.toCurrencyId)
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
                        ps.setBigDecimal(1, used.add(request.creditAmount))
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
                    INSERT INTO rondo_exchange_record
                        (player_uuid, rule_id, amount, created_at)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """.trimIndent()).use { ps ->
                    ps.setString(1, request.playerUuid.toString())
                    ps.setString(2, request.ruleId)
                    ps.setBigDecimal(3, request.creditAmount)
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
            INSERT INTO rondo_balance
                (player_uuid, currency_id, balance, total_earned, total_spent)
            VALUES (?, ?, ?, 0, 0)
            ON DUPLICATE KEY UPDATE player_uuid = VALUES(player_uuid)
        """.trimIndent()).use { ps ->
            ps.setString(1, playerUuid.toString())
            ps.setString(2, currencyId)
            ps.setBigDecimal(3, initialBalance)
            ps.executeUpdate()
        }
    }

    private fun readBalanceForUpdate(
        conn: Connection,
        playerUuid: UUID,
        currencyId: String
    ): BalanceData {
        conn.prepareStatement("""
            SELECT balance, total_earned, total_spent
            FROM rondo_balance
            WHERE player_uuid = ? AND currency_id = ?
            FOR UPDATE
        """.trimIndent()).use { ps ->
            ps.setString(1, playerUuid.toString())
            ps.setString(2, currencyId)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "余额记录初始化失败: $playerUuid/$currencyId" }
                return BalanceData(
                    balance = rs.getBigDecimal("balance"),
                    totalEarned = rs.getBigDecimal("total_earned"),
                    totalSpent = rs.getBigDecimal("total_spent")
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
            SET balance = ?, total_earned = ?, total_spent = ?
            WHERE player_uuid = ? AND currency_id = ?
        """.trimIndent()).use { ps ->
            ps.setBigDecimal(1, data.balance)
            ps.setBigDecimal(2, data.totalEarned)
            ps.setBigDecimal(3, data.totalSpent)
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
            "累计获得超出 DECIMAL(38,4) 可存储范围"
        }
        require(MoneyConstraints.isCumulativeStorable(data.totalSpent)) {
            "累计消耗超出 DECIMAL(38,4) 可存储范围"
        }
    }

    private fun lockAndReadExchangeQuota(
        conn: Connection,
        request: ExchangeBalanceRequest
    ): BigDecimal {
        conn.prepareStatement("""
            INSERT INTO rondo_exchange_quota
                (player_uuid, rule_id, period_start, amount)
            VALUES (?, ?, ?, 0)
            ON DUPLICATE KEY UPDATE player_uuid = VALUES(player_uuid)
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

        return conn.prepareStatement("""
            SELECT amount FROM rondo_exchange_quota
            WHERE player_uuid = ? AND rule_id = ? AND period_start = ?
            FOR UPDATE
        """.trimIndent()).use { ps ->
            ps.setString(1, request.playerUuid.toString())
            ps.setString(2, request.ruleId)
            ps.setLong(3, request.periodStart!!)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "兑换额度记录初始化失败" }
                rs.getBigDecimal("amount")
            }
        }
    }

    override fun savePlayerName(playerUuid: UUID, playerName: String) {
        getConnection().use { conn ->
            conn.prepareStatement("""
                INSERT INTO rondo_player (player_uuid, player_name)
                VALUES (?, ?)
                ON DUPLICATE KEY UPDATE player_name = VALUES(player_name)
            """.trimIndent()).use { ps ->
                ps.setString(1, playerUuid.toString())
                ps.setString(2, playerName)
                ps.executeUpdate()
            }
        }
    }

    override fun getBalance(playerUuid: UUID, currencyId: String): BalanceData? {
        getConnection().use { conn ->
            conn.prepareStatement("SELECT balance, total_earned, total_spent FROM rondo_balance WHERE player_uuid = ? AND currency_id = ?").use { ps ->
                ps.setString(1, playerUuid.toString())
                ps.setString(2, currencyId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        return BalanceData(
                            balance = rs.getBigDecimal("balance"),
                            totalEarned = rs.getBigDecimal("total_earned"),
                            totalSpent = rs.getBigDecimal("total_spent")
                        )
                    }
                }
            }
        }
        return null
    }

    override fun insertLog(log: TransactionLog) {
        getConnection().use { conn ->
            conn.prepareStatement("""
                INSERT INTO rondo_log (player_uuid, currency_id, action, amount, balance, source, detail, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()).use { ps ->
                ps.setString(1, log.playerUuid.toString())
                ps.setString(2, log.currencyId)
                ps.setString(3, log.action.name)
                ps.setBigDecimal(4, log.amount)
                ps.setBigDecimal(5, log.balanceAfter)
                ps.setString(6, log.source)
                ps.setString(7, log.detail)
                ps.setTimestamp(8, Timestamp(log.timestamp))
                ps.executeUpdate()
            }
        }
    }

    override fun insertLogsBatch(logs: List<TransactionLog>) {
        if (logs.isEmpty()) return
        getConnection().use { conn ->
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
                        ps.setBigDecimal(4, log.amount)
                        ps.setBigDecimal(5, log.balanceAfter)
                        ps.setString(6, log.source)
                        ps.setString(7, log.detail)
                        ps.setTimestamp(8, Timestamp(log.timestamp))
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
            "SELECT * FROM rondo_log WHERE player_uuid = ? AND currency_id = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?"
        } else {
            "SELECT * FROM rondo_log WHERE player_uuid = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?"
        }
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                ps.setString(idx++, playerUuid.toString())
                if (currencyId != null) ps.setString(idx++, currencyId)
                ps.setInt(idx++, pageSize)
                ps.setLong(idx, offset)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        result.add(TransactionLog(
                            playerUuid = UUID.fromString(rs.getString("player_uuid")),
                            currencyId = rs.getString("currency_id"),
                            action = TransactionLog.Action.valueOf(rs.getString("action")),
                            amount = rs.getBigDecimal("amount"),
                            balanceAfter = rs.getBigDecimal("balance"),
                            source = rs.getString("source"),
                            detail = rs.getString("detail"),
                            timestamp = rs.getTimestamp("created_at").time
                        ))
                    }
                }
            }
        }
        return result
    }

    override fun cleanExpiredData(retentionDays: Int, exchangeQuotaCutoffMillis: Long) {
        getConnection().use { conn ->
            val deletedLogs: Int
            val deletedExchanges: Int
            if (retentionDays > 0) {
                deletedLogs = deleteTimestampedRowsInBatches(conn, "rondo_log", retentionDays)
                deletedExchanges = deleteTimestampedRowsInBatches(
                    conn,
                    "rondo_exchange_record",
                    retentionDays
                )
            } else {
                deletedLogs = 0
                deletedExchanges = 0
            }
            val deletedQuotas = deleteQuotaRowsInBatches(conn, exchangeQuotaCutoffMillis)
            if (deletedLogs > 0 || deletedExchanges > 0 || deletedQuotas > 0) {
                BlinkLog.info(
                    "Cleaned $deletedLogs expired transaction logs and " +
                        "$deletedExchanges exchange audit records; " +
                        "removed $deletedQuotas expired exchange quota rows."
                )
            }
        }
    }

    private fun deleteTimestampedRowsInBatches(
        conn: Connection,
        table: String,
        retentionDays: Int
    ): Int {
        var total = 0
        do {
            val deleted = conn.prepareStatement("""
                DELETE FROM $table
                WHERE created_at < DATE_SUB(NOW(), INTERVAL ? DAY)
                ORDER BY created_at, id
                LIMIT ?
            """.trimIndent()).use { ps ->
                ps.setInt(1, retentionDays)
                ps.setInt(2, CLEANUP_BATCH_SIZE)
                ps.executeUpdate()
            }
            total += deleted
        } while (deleted == CLEANUP_BATCH_SIZE)
        return total
    }

    private fun deleteQuotaRowsInBatches(conn: Connection, cutoffMillis: Long): Int {
        if (cutoffMillis <= 0L) return 0
        var total = 0
        do {
            val deleted = conn.prepareStatement("""
                DELETE FROM rondo_exchange_quota
                WHERE period_start < ?
                ORDER BY period_start, player_uuid, rule_id
                LIMIT ?
            """.trimIndent()).use { ps ->
                ps.setLong(1, cutoffMillis)
                ps.setInt(2, CLEANUP_BATCH_SIZE)
                ps.executeUpdate()
            }
            total += deleted
        } while (deleted == CLEANUP_BATCH_SIZE)
        return total
    }

    override fun queryRanking(currencyId: String, limit: Int): List<RankingData> {
        val result = mutableListOf<RankingData>()
        getConnection().use { conn ->
            conn.prepareStatement("""
                SELECT b.player_uuid, b.balance, p.player_name
                FROM rondo_balance b
                LEFT JOIN rondo_player p ON p.player_uuid = b.player_uuid
                WHERE b.currency_id = ?
                ORDER BY b.balance DESC, b.player_uuid ASC
                LIMIT ?
            """.trimIndent()).use { ps ->
                ps.setString(1, currencyId)
                ps.setInt(2, limit)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        result.add(RankingData(
                            playerUuid = UUID.fromString(rs.getString("player_uuid")),
                            playerName = rs.getString("player_name"),
                            balance = rs.getBigDecimal("balance")
                        ))
                    }
                }
            }
        }
        return result
    }

    override fun queryPlayerRank(playerUuid: UUID, currencyId: String): Int? {
        getConnection().use { conn ->
            conn.prepareStatement("""
                SELECT (
                    SELECT COUNT(*) + 1
                    FROM rondo_balance b
                    WHERE b.currency_id = target.currency_id
                      AND (
                          b.balance > target.balance
                          OR (b.balance = target.balance AND b.player_uuid < target.player_uuid)
                      )
                ) AS rank
                FROM rondo_balance target
                WHERE target.player_uuid = ? AND target.currency_id = ?
            """.trimIndent()).use { ps ->
                ps.setString(1, playerUuid.toString())
                ps.setString(2, currencyId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) return rs.getInt("rank")
                }
            }
        }
        return null
    }

}
