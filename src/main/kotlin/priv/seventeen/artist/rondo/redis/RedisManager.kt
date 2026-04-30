package priv.seventeen.artist.rondo.redis

import priv.seventeen.artist.blink.BlinkLog
import priv.seventeen.artist.rondo.config.MainConfig
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import redis.clients.jedis.JedisPubSub

/**
 * Redis 连接池管理 + Pub/Sub
 */
object RedisManager {

    private lateinit var pool: JedisPool
    private var subThread: Thread? = null
    private var pubSub: JedisPubSub? = null
    private var channel: String = "rondo:sync"

    val isEnabled get() = ::pool.isInitialized

    fun initialize(config: MainConfig) {
        val redis = config.crossServer.redis
        channel = config.crossServer.syncChannel

        val poolConfig = JedisPoolConfig().apply {
            maxTotal = redis.poolSize
            maxIdle = redis.poolSize
            minIdle = 1
            testOnBorrow = true
        }

        pool = if (redis.password.isNotEmpty()) {
            JedisPool(poolConfig, redis.host, redis.port, 3000, redis.password, redis.database)
        } else {
            JedisPool(poolConfig, redis.host, redis.port, 3000, null, redis.database)
        }

        // 验证连接
        try {
            pool.resource.use { it.ping() }
            BlinkLog.success("Redis 已连接 §7(${redis.host}:${redis.port})")
        } catch (e: Exception) {
            BlinkLog.error("Redis 连接失败: ${e.message}")
            throw e
        }

        // 启动 Pub/Sub 订阅线程
        startSubscriber()
    }

    /** 获取 Jedis 连接 */
    fun <T> use(block: (redis.clients.jedis.Jedis) -> T): T {
        return pool.resource.use(block)
    }

    /** 发布消息 */
    fun publish(message: String) {
        try {
            pool.resource.use { it.publish(channel, message) }
        } catch (e: Exception) {
            BlinkLog.warn("Redis publish 失败: ${e.message}")
        }
    }

    private fun startSubscriber() {
        pubSub = RedisSyncListener()
        subThread = Thread({
            try {
                pool.resource.use { jedis ->
                    jedis.subscribe(pubSub, channel)
                }
            } catch (e: Exception) {
                if (e !is InterruptedException && pubSub != null) {
                    BlinkLog.warn("Redis 订阅断开: ${e.message}")
                }
            }
        }, "Rondo-Redis-Sub").apply {
            isDaemon = true
            start()
        }
        BlinkLog.info("Redis Pub/Sub 已订阅 §7($channel)")
    }

    fun shutdown() {
        try {
            pubSub?.unsubscribe()
            pubSub = null
            subThread?.interrupt()
            subThread = null
            if (::pool.isInitialized) {
                pool.close()
            }
        } catch (_: Exception) {}
    }
}
