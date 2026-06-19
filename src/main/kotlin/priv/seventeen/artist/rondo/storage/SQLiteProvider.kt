package priv.seventeen.artist.rondo.storage

import priv.seventeen.artist.blink.BlinkLog
import priv.seventeen.artist.blink.bukkitPlugin
import priv.seventeen.artist.rondo.log.TransactionLog
import java.io.File
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * SQLite 存储实现
 */
class SQLiteProvider : StorageProvider {

    private lateinit var connection: Connection
    private val lock = Any()

    override fun initialize() {
        val dbFile = File(bukkitPlugin.dataFolder, "data.db")
        if (!dbFile.parentFile.exists()) dbFile.parentFile.mkdirs()
        connection = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
        connection.createStatement().use { stmt ->
            stmt.executeUpdate("PRAGMA journal_mode=WAL")
            stmt.executeUpdate("PRAGMA synchronous=NORMAL")
        }
        createTables()
        BlinkLog.info("SQLite storage initialized.")
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
                val dbFile = File(bukkitPlugin.dataFolder, "data.db")
                connection = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
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

    override fun saveBalance(playerUuid: UUID, currencyId: String, data: BalanceData) {
        synchronized(lock) {
            getConnection().prepareStatement("""
                INSERT OR REPLACE INTO rondo_balance (player_uuid, currency_id, balance, total_earned, total_spent, updated_at)
                VALUES (?, ?, ?, ?, ?, datetime('now'))
            """.trimIndent()).use { ps ->
                ps.setString(1, playerUuid.toString())
                ps.setString(2, currencyId)
                ps.setString(3, data.balance.toPlainString())
                ps.setString(4, data.totalEarned.toPlainString())
                ps.setString(5, data.totalSpent.toPlainString())
                ps.executeUpdate()
            }
        }
    }

    override fun saveBalancesBatch(entries: List<BalanceEntry>) {
        if (entries.isEmpty()) return
        synchronized(lock) {
            val conn = getConnection()
            conn.autoCommit = false
            try {
                conn.prepareStatement("""
                    INSERT OR REPLACE INTO rondo_balance (player_uuid, currency_id, balance, total_earned, total_spent, updated_at)
                    VALUES (?, ?, ?, ?, ?, datetime('now'))
                """.trimIndent()).use { ps ->
                    for (entry in entries) {
                        ps.setString(1, entry.playerUuid.toString())
                        ps.setString(2, entry.currencyId)
                        ps.setString(3, entry.data.balance.toPlainString())
                        ps.setString(4, entry.data.totalEarned.toPlainString())
                        ps.setString(5, entry.data.totalSpent.toPlainString())
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

    override fun updateOfflineBalance(playerUuid: UUID, currencyId: String, delta: BigDecimal, source: String, allowNegative: Boolean, maxBalance: BigDecimal?): Boolean {
        synchronized(lock) {
            val conn = getConnection()
            conn.autoCommit = false
            try {
                // 确保记录存在
                conn.prepareStatement("""
                    INSERT OR IGNORE INTO rondo_balance (player_uuid, currency_id, balance, total_earned, total_spent)
                    VALUES (?, ?, '0', '0', '0')
                """.trimIndent()).use { ps ->
                    ps.setString(1, playerUuid.toString())
                    ps.setString(2, currencyId)
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
                if (delta < BigDecimal.ZERO && newBalance < BigDecimal.ZERO && !allowNegative) {
                    conn.rollback()
                    return false
                }
                // 检查余额上限（存入时），与在线 Account.deposit 保持一致
                if (delta > BigDecimal.ZERO && maxBalance != null && newBalance > maxBalance) {
                    conn.rollback()
                    return false
                }

                // 更新余额
                val newEarned = if (delta >= BigDecimal.ZERO) earned.add(delta) else earned
                val newSpent = if (delta < BigDecimal.ZERO) spent.add(delta.abs()) else spent

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
                ps.setString(8, java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(log.timestamp)))
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
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                    for (log in logs) {
                        ps.setString(1, log.playerUuid.toString())
                        ps.setString(2, log.currencyId)
                        ps.setString(3, log.action.name)
                        ps.setString(4, log.amount.toPlainString())
                        ps.setString(5, log.balanceAfter.toPlainString())
                        ps.setString(6, log.source)
                        ps.setString(7, log.detail)
                        ps.setString(8, sdf.format(java.util.Date(log.timestamp)))
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
        synchronized(lock) {
            getConnection().prepareStatement(sql).use { ps ->
                var idx = 1
                ps.setString(idx++, playerUuid.toString())
                if (currencyId != null) ps.setString(idx++, currencyId)
                ps.setInt(idx++, pageSize)
                ps.setInt(idx, (page - 1) * pageSize)
                ps.executeQuery().use { rs ->
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                    while (rs.next()) {
                        val timeStr = rs.getString("created_at")
                        val time = try { sdf.parse(timeStr)?.time ?: 0L } catch (_: Exception) { 0L }
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
            getConnection().prepareStatement("DELETE FROM rondo_log WHERE created_at < datetime('now', ?)").use { ps ->
                ps.setString(1, "-$retentionDays days")
                val deleted = ps.executeUpdate()
                if (deleted > 0) {
                    BlinkLog.info("Cleaned $deleted expired log entries.")
                }
            }
        }
    }

    override fun queryRanking(currencyId: String, limit: Int): List<RankingData> {
        val result = mutableListOf<RankingData>()
        synchronized(lock) {
            getConnection().prepareStatement("SELECT player_uuid, balance FROM rondo_balance WHERE currency_id = ? ORDER BY CAST(balance AS REAL) DESC LIMIT ?").use { ps ->
                ps.setString(1, currencyId)
                ps.setInt(2, limit)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        result.add(RankingData(
                            playerUuid = UUID.fromString(rs.getString("player_uuid")),
                            playerName = null,
                            balance = BigDecimal(rs.getString("balance") ?: "0")
                        ))
                    }
                }
            }
        }
        return result
    }

    override fun queryPlayerRank(playerUuid: UUID, currencyId: String): Int? {
        synchronized(lock) {
            getConnection().prepareStatement("""
                SELECT COUNT(*) + 1 AS rank FROM rondo_balance 
                WHERE currency_id = ? AND CAST(balance AS REAL) > (
                    SELECT COALESCE(CAST(balance AS REAL), 0) FROM rondo_balance WHERE player_uuid = ? AND currency_id = ?
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
        synchronized(lock) {
            getConnection().prepareStatement("INSERT INTO rondo_exchange_record (player_uuid, rule_id, amount) VALUES (?, ?, ?)").use { ps ->
                ps.setString(1, playerUuid.toString())
                ps.setString(2, ruleId)
                ps.setString(3, amount.toPlainString())
                ps.executeUpdate()
            }
        }
    }

    override fun queryExchangeCount(playerUuid: UUID, ruleId: String, sinceTimestamp: Long): BigDecimal {
        synchronized(lock) {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
            val sinceStr = sdf.format(java.util.Date(sinceTimestamp))
            getConnection().prepareStatement("SELECT COALESCE(SUM(CAST(amount AS REAL)), 0) AS total FROM rondo_exchange_record WHERE player_uuid = ? AND rule_id = ? AND created_at >= ?").use { ps ->
                ps.setString(1, playerUuid.toString())
                ps.setString(2, ruleId)
                ps.setString(3, sinceStr)
                ps.executeQuery().use { rs ->
                    if (rs.next()) return BigDecimal(rs.getString("total") ?: "0")
                }
            }
        }
        return BigDecimal.ZERO
    }
}
