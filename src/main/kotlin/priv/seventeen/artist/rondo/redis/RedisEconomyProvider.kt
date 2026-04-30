package priv.seventeen.artist.rondo.redis

import priv.seventeen.artist.rondo.currency.Currency
import priv.seventeen.artist.rondo.storage.BalanceData
import java.math.BigDecimal
import java.util.UUID

/**
 * Redis 原子经济操作 — 所有余额校验+修改在 Lua 脚本中一步完成
 */
object RedisEconomyProvider {

    // Redis key 前缀
    private const val KEY_BALANCE = "rondo:bal"
    private const val KEY_EARNED = "rondo:earned"
    private const val KEY_SPENT = "rondo:spent"

    // ===== Lua 脚本 =====

    /** 存入: 校验上限 + 增加余额 + 累计 earned，原子完成 */
    private val DEPOSIT_LUA = """
        local bal = tonumber(redis.call('HGET', KEYS[1], ARGV[1]) or '0')
        local amt = tonumber(ARGV[2])
        local maxBal = tonumber(ARGV[3])
        if maxBal >= 0 and bal + amt > maxBal then return 0 end
        local newBal = bal + amt
        redis.call('HSET', KEYS[1], ARGV[1], string.format('%.4f', newBal))
        local earned = tonumber(redis.call('HGET', KEYS[2], ARGV[1]) or '0')
        redis.call('HSET', KEYS[2], ARGV[1], string.format('%.4f', earned + amt))
        redis.call('PUBLISH', ARGV[4], ARGV[5])
        return 1
    """.trimIndent()

    /** 扣除: 校验余额 + 减少余额 + 累计 spent，原子完成 */
    private val WITHDRAW_LUA = """
        local bal = tonumber(redis.call('HGET', KEYS[1], ARGV[1]) or '0')
        local amt = tonumber(ARGV[2])
        local allowNeg = ARGV[3] == '1'
        if not allowNeg and bal - amt < 0 then return 0 end
        local newBal = bal - amt
        redis.call('HSET', KEYS[1], ARGV[1], string.format('%.4f', newBal))
        local spent = tonumber(redis.call('HGET', KEYS[2], ARGV[1]) or '0')
        redis.call('HSET', KEYS[2], ARGV[1], string.format('%.4f', spent + amt))
        redis.call('PUBLISH', ARGV[3 + (allowNeg and 0 or 0)], ARGV[4 + (allowNeg and 0 or 0)])
        return 1
    """.trimIndent()

    // 简化版 — 参数位置固定
    private val WITHDRAW_SCRIPT = """
        local bal = tonumber(redis.call('HGET', KEYS[1], ARGV[1]) or '0')
        local amt = tonumber(ARGV[2])
        local allowNeg = ARGV[3] == '1'
        if not allowNeg and bal - amt < 0 then return 0 end
        local newBal = bal - amt
        redis.call('HSET', KEYS[1], ARGV[1], string.format('%.4f', newBal))
        local spent = tonumber(redis.call('HGET', KEYS[2], ARGV[1]) or '0')
        redis.call('HSET', KEYS[2], ARGV[1], string.format('%.4f', spent + amt))
        redis.call('PUBLISH', ARGV[4], ARGV[5])
        return 1
    """.trimIndent()

    /** 设置余额: 直接覆盖 */
    private val SET_LUA = """
        redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
        redis.call('PUBLISH', ARGV[3], ARGV[4])
        return 1
    """.trimIndent()

    // ===== 公开方法 =====

    /** 存入货币（原子操作） */
    fun deposit(playerUuid: UUID, currencyId: String, amount: BigDecimal, currency: Currency): Boolean {
        val key = "$KEY_BALANCE:$playerUuid"
        val earnedKey = "$KEY_EARNED:$playerUuid"
        val channel = "rondo:sync"
        val message = "$playerUuid:$currencyId"
        val maxBal = if (currency.hasMaxBalance) currency.maxBalance.toPlainString() else "-1"

        val result = RedisManager.use { jedis ->
            jedis.eval(
                DEPOSIT_LUA,
                listOf(key, earnedKey),
                listOf(currencyId, amount.toPlainString(), maxBal, channel, message)
            )
        }
        return (result as Long) == 1L
    }

    /** 扣除货币（原子操作） */
    fun withdraw(playerUuid: UUID, currencyId: String, amount: BigDecimal, allowNegative: Boolean): Boolean {
        val key = "$KEY_BALANCE:$playerUuid"
        val spentKey = "$KEY_SPENT:$playerUuid"
        val channel = "rondo:sync"
        val message = "$playerUuid:$currencyId"

        val result = RedisManager.use { jedis ->
            jedis.eval(
                WITHDRAW_SCRIPT,
                listOf(key, spentKey),
                listOf(currencyId, amount.toPlainString(), if (allowNegative) "1" else "0", channel, message)
            )
        }
        return (result as Long) == 1L
    }

    /** 设置余额 */
    fun setBalance(playerUuid: UUID, currencyId: String, amount: BigDecimal): Boolean {
        val key = "$KEY_BALANCE:$playerUuid"
        val channel = "rondo:sync"
        val message = "$playerUuid:$currencyId"

        val result = RedisManager.use { jedis ->
            jedis.eval(
                SET_LUA,
                listOf(key),
                listOf(currencyId, amount.toPlainString(), channel, message)
            )
        }
        return (result as Long) == 1L
    }

    /** 获取余额 */
    fun getBalance(playerUuid: UUID, currencyId: String): BigDecimal {
        return RedisManager.use { jedis ->
            val raw = jedis.hget("$KEY_BALANCE:$playerUuid", currencyId)
            if (raw != null) BigDecimal(raw) else BigDecimal.ZERO
        }
    }

    /** 获取完整余额数据 */
    fun getBalanceData(playerUuid: UUID, currencyId: String): BalanceData {
        return RedisManager.use { jedis ->
            val bal = jedis.hget("$KEY_BALANCE:$playerUuid", currencyId)
            val earned = jedis.hget("$KEY_EARNED:$playerUuid", currencyId)
            val spent = jedis.hget("$KEY_SPENT:$playerUuid", currencyId)
            BalanceData(
                balance = if (bal != null) BigDecimal(bal) else BigDecimal.ZERO,
                totalEarned = if (earned != null) BigDecimal(earned) else BigDecimal.ZERO,
                totalSpent = if (spent != null) BigDecimal(spent) else BigDecimal.ZERO
            )
        }
    }

    /** 加载玩家所有货币余额 */
    fun loadAllBalances(playerUuid: UUID): Map<String, BalanceData> {
        return RedisManager.use { jedis ->
            val balMap = jedis.hgetAll("$KEY_BALANCE:$playerUuid") ?: emptyMap()
            val earnedMap = jedis.hgetAll("$KEY_EARNED:$playerUuid") ?: emptyMap()
            val spentMap = jedis.hgetAll("$KEY_SPENT:$playerUuid") ?: emptyMap()

            val allKeys = balMap.keys + earnedMap.keys + spentMap.keys
            allKeys.associateWith { cid ->
                BalanceData(
                    balance = balMap[cid]?.let { BigDecimal(it) } ?: BigDecimal.ZERO,
                    totalEarned = earnedMap[cid]?.let { BigDecimal(it) } ?: BigDecimal.ZERO,
                    totalSpent = spentMap[cid]?.let { BigDecimal(it) } ?: BigDecimal.ZERO
                )
            }
        }
    }

    /** 将数据库数据同步到 Redis（首次迁移或启动时） */
    fun syncFromDatabase(playerUuid: UUID, data: Map<String, BalanceData>) {
        if (data.isEmpty()) return
        RedisManager.use { jedis ->
            val pipe = jedis.pipelined()
            for ((cid, bd) in data) {
                pipe.hset("$KEY_BALANCE:$playerUuid", cid, bd.balance.toPlainString())
                pipe.hset("$KEY_EARNED:$playerUuid", cid, bd.totalEarned.toPlainString())
                pipe.hset("$KEY_SPENT:$playerUuid", cid, bd.totalSpent.toPlainString())
            }
            pipe.sync()
        }
    }

    /** 将 Redis 数据备份到数据库 */
    fun backupToDatabase(playerUuid: UUID): Map<String, BalanceData> {
        return loadAllBalances(playerUuid)
    }
}
