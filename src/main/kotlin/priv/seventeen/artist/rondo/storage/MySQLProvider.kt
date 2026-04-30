package priv.seventeen.artist.rondo.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import priv.seventeen.artist.blink.BlinkLog
import priv.seventeen.artist.rondo.config.MainConfig
import priv.seventeen.artist.rondo.log.TransactionLog
import java.math.BigDecimal
import java.sql.Connection
import java.sql.Timestamp
import java.util.UUID

/**
 * MySQL 存储实现
 */
class MySQLProvider(private val config: MainConfig) : StorageProvider {

    private lateinit var dataSource: HikariDataSource

    override fun initialize() {
        val mysql = config.storage.mysql
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = "jdbc:mysql://${mysql.host}:${mysql.port}/${mysql.database}?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8&useUnicode=true"
            username = mysql.username
            password = mysql.password
            maximumPoolSize = mysql.poolSize
            minimumIdle = 2
            connectionTimeout = 10000
            idleTimeout = 600000
            maxLifetime = 1800000
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
                        total_earned DECIMAL(20,4)  NOT NULL DEFAULT 0,
                        total_spent  DECIMAL(20,4)  NOT NULL DEFAULT 0,
                        updated_at   TIMESTAMP      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        PRIMARY KEY (player_uuid, currency_id)
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
                        INDEX idx_player_time (player_uuid, created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent())

                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS rondo_exchange_record (
                        id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                        player_uuid CHAR(36)       NOT NULL,
                        rule_id     VARCHAR(64)    NOT NULL,
                        amount      DECIMAL(20,4)  NOT NULL,
                        created_at  TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,
                        INDEX idx_player_rule (player_uuid, rule_id, created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent())
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

    override fun saveBalance(playerUuid: UUID, currencyId: String, data: BalanceData) {
        getConnection().use { conn ->
            conn.prepareStatement("""
                INSERT INTO rondo_balance (player_uuid, currency_id, balance, total_earned, total_spent)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE balance = VALUES(balance), total_earned = VALUES(total_earned), total_spent = VALUES(total_spent)
            """.trimIndent()).use { ps ->
                ps.setString(1, playerUuid.toString())
                ps.setString(2, currencyId)
                ps.setBigDecimal(3, data.balance)
                ps.setBigDecimal(4, data.totalEarned)
                ps.setBigDecimal(5, data.totalSpent)
                ps.executeUpdate()
            }
        }
    }

    override fun saveBalancesBatch(entries: List<BalanceEntry>) {
        if (entries.isEmpty()) return
        getConnection().use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement("""
                    INSERT INTO rondo_balance (player_uuid, currency_id, balance, total_earned, total_spent)
                    VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE balance = VALUES(balance), total_earned = VALUES(total_earned), total_spent = VALUES(total_spent)
                """.trimIndent()).use { ps ->
                    for (entry in entries) {
                        ps.setString(1, entry.playerUuid.toString())
                        ps.setString(2, entry.currencyId)
                        ps.setBigDecimal(3, entry.data.balance)
                        ps.setBigDecimal(4, entry.data.totalEarned)
                        ps.setBigDecimal(5, entry.data.totalSpent)
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

    override fun updateOfflineBalance(playerUuid: UUID, currencyId: String, delta: BigDecimal, source: String, allowNegative: Boolean): Boolean {
        getConnection().use { conn ->
            conn.autoCommit = false
            try {
                // 先确保记录存在
                conn.prepareStatement("""
                    INSERT IGNORE INTO rondo_balance (player_uuid, currency_id, balance, total_earned, total_spent)
                    VALUES (?, ?, 0, 0, 0)
                """.trimIndent()).use { ps ->
                    ps.setString(1, playerUuid.toString())
                    ps.setString(2, currencyId)
                    ps.executeUpdate()
                }

                // 更新余额
                val sql = if (delta >= BigDecimal.ZERO) {
                    "UPDATE rondo_balance SET balance = balance + ?, total_earned = total_earned + ? WHERE player_uuid = ? AND currency_id = ?"
                } else if (allowNegative) {
                    "UPDATE rondo_balance SET balance = balance + ?, total_spent = total_spent + ? WHERE player_uuid = ? AND currency_id = ?"
                } else {
                    "UPDATE rondo_balance SET balance = balance + ?, total_spent = total_spent + ? WHERE player_uuid = ? AND currency_id = ? AND balance + ? >= 0"
                }
                conn.prepareStatement(sql).use { ps ->
                    ps.setBigDecimal(1, delta)
                    ps.setBigDecimal(2, delta.abs())
                    ps.setString(3, playerUuid.toString())
                    ps.setString(4, currencyId)
                    if (delta < BigDecimal.ZERO && !allowNegative) {
                        ps.setBigDecimal(5, delta)
                    }
                    val rows = ps.executeUpdate()
                    if (rows == 0) {
                        conn.rollback()
                        return false
                    }
                }
                conn.commit()
                return true
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun getOfflineBalance(playerUuid: UUID, currencyId: String): BalanceData? {
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
        val sql = if (currencyId != null) {
            "SELECT * FROM rondo_log WHERE player_uuid = ? AND currency_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?"
        } else {
            "SELECT * FROM rondo_log WHERE player_uuid = ? ORDER BY created_at DESC LIMIT ? OFFSET ?"
        }
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                ps.setString(idx++, playerUuid.toString())
                if (currencyId != null) ps.setString(idx++, currencyId)
                ps.setInt(idx++, pageSize)
                ps.setInt(idx, (page - 1) * pageSize)
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

    override fun cleanExpiredLogs(retentionDays: Int) {
        if (retentionDays <= 0) return
        getConnection().use { conn ->
            conn.prepareStatement("DELETE FROM rondo_log WHERE created_at < DATE_SUB(NOW(), INTERVAL ? DAY)").use { ps ->
                ps.setInt(1, retentionDays)
                val deleted = ps.executeUpdate()
                if (deleted > 0) {
                    BlinkLog.info("Cleaned $deleted expired log entries.")
                }
            }
        }
    }

    override fun queryRanking(currencyId: String, limit: Int): List<RankingData> {
        val result = mutableListOf<RankingData>()
        getConnection().use { conn ->
            conn.prepareStatement("SELECT player_uuid, balance FROM rondo_balance WHERE currency_id = ? ORDER BY balance DESC LIMIT ?").use { ps ->
                ps.setString(1, currencyId)
                ps.setInt(2, limit)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        result.add(RankingData(
                            playerUuid = UUID.fromString(rs.getString("player_uuid")),
                            playerName = null,
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
                SELECT COUNT(*) + 1 AS rank FROM rondo_balance 
                WHERE currency_id = ? AND balance > (
                    SELECT COALESCE(balance, 0) FROM rondo_balance WHERE player_uuid = ? AND currency_id = ?
                )
            """.trimIndent()).use { ps ->
                ps.setString(1, currencyId)
                ps.setString(2, playerUuid.toString())
                ps.setString(3, currencyId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) return rs.getInt("rank")
                }
            }
        }
        return null
    }

    override fun insertExchangeRecord(playerUuid: UUID, ruleId: String, amount: BigDecimal) {
        getConnection().use { conn ->
            conn.prepareStatement("INSERT INTO rondo_exchange_record (player_uuid, rule_id, amount) VALUES (?, ?, ?)").use { ps ->
                ps.setString(1, playerUuid.toString())
                ps.setString(2, ruleId)
                ps.setBigDecimal(3, amount)
                ps.executeUpdate()
            }
        }
    }

    override fun queryExchangeCount(playerUuid: UUID, ruleId: String, sinceTimestamp: Long): BigDecimal {
        getConnection().use { conn ->
            conn.prepareStatement("SELECT COALESCE(SUM(amount), 0) AS total FROM rondo_exchange_record WHERE player_uuid = ? AND rule_id = ? AND created_at >= ?").use { ps ->
                ps.setString(1, playerUuid.toString())
                ps.setString(2, ruleId)
                ps.setTimestamp(3, Timestamp(sinceTimestamp))
                ps.executeQuery().use { rs ->
                    if (rs.next()) return rs.getBigDecimal("total")
                }
            }
        }
        return BigDecimal.ZERO
    }
}
