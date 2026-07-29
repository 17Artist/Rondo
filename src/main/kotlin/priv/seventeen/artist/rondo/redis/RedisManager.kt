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

package priv.seventeen.artist.rondo.redis

import priv.seventeen.artist.blink.BlinkLog
import priv.seventeen.artist.rondo.account.AccountManager
import priv.seventeen.artist.rondo.config.MainConfig
import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import redis.clients.jedis.JedisPubSub
import java.util.UUID

/**
 * Redis 连接池管理 + Pub/Sub
 */
object RedisManager {

    private lateinit var pool: JedisPool
    private var subThread: Thread? = null
    private var pubSub: JedisPubSub? = null
    private var channel: String = "rondo:sync"
    @Volatile
    private var running = false

    /** 本服务端唯一 ID，用于 Pub/Sub 消息过滤 */
    val serverId: String = UUID.randomUUID().toString().substring(0, 8)

    val isEnabled get() = running && ::pool.isInitialized && !pool.isClosed

    fun initialize(config: MainConfig) {
        val redis = config.crossServer.redis
        channel = config.crossServer.syncChannel

        val poolConfig = JedisPoolConfig().apply {
            maxTotal = redis.poolSize
            maxIdle = redis.poolSize
            minIdle = 1
            testOnBorrow = true
            testWhileIdle = true
        }

        val clientConfigBuilder = DefaultJedisClientConfig.builder()
            .connectionTimeoutMillis(redis.timeoutMillis)
            .socketTimeoutMillis(redis.timeoutMillis)
            .database(redis.database)
            .clientName("Rondo-$serverId")
            .ssl(redis.ssl)
        if (redis.username.isNotEmpty()) {
            clientConfigBuilder.user(redis.username)
        }
        if (redis.password.isNotEmpty()) {
            clientConfigBuilder.password(redis.password)
        }
        val candidatePool = JedisPool(
            poolConfig,
            HostAndPort(redis.host, redis.port),
            clientConfigBuilder.build()
        )

        // 验证连接
        try {
            candidatePool.resource.use { it.ping() }
            pool = candidatePool
            running = true
            BlinkLog.success("Redis 已连接 §7(${redis.host}:${redis.port}, id=$serverId)")
        } catch (e: Exception) {
            candidatePool.close()
            BlinkLog.error("Redis 连接失败: ${e.message}")
            throw e
        }

        // 启动 Pub/Sub 订阅线程
        startSubscriber()
    }

    /** 发布同步消息，格式: {serverId}:{uuid}:{currencyId} */
    fun publishSync(playerUuid: UUID, currencyId: String) {
        val message = "$serverId:$playerUuid:$currencyId"
        try {
            pool.resource.use { it.publish(channel, message) }
        } catch (e: Exception) {
            BlinkLog.warn("Redis publish 失败: ${e.message}")
        }
    }

    private fun startSubscriber() {
        subThread = Thread({
            var retryMillis = 1_000L
            var subscribedOnce = false
            while (running && !Thread.currentThread().isInterrupted) {
                val listener = RedisSyncListener {
                    if (subscribedOnce) {
                        try {
                            AccountManager.reconcileOnlineSnapshotsAfterRedisReconnect()
                        } catch (failure: Throwable) {
                            BlinkLog.warn("Redis 重连后调度经济快照校准失败: ${failure.message}")
                        }
                    } else {
                        subscribedOnce = true
                    }
                }
                pubSub = listener
                try {
                    pool.resource.use { jedis ->
                        jedis.subscribe(listener, channel)
                    }
                    retryMillis = 1_000L
                } catch (e: Exception) {
                    if (running && !Thread.currentThread().isInterrupted) {
                        BlinkLog.warn("Redis 订阅断开，将在 ${retryMillis}ms 后重连: ${e.message}")
                    }
                } finally {
                    if (pubSub === listener) pubSub = null
                }
                if (running && !Thread.currentThread().isInterrupted) {
                    try {
                        Thread.sleep(retryMillis)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    retryMillis = (retryMillis * 2).coerceAtMost(30_000L)
                }
            }
        }, "Rondo-Redis-Sub").apply {
            isDaemon = true
            start()
        }
        BlinkLog.info("Redis Pub/Sub 已订阅 §7($channel)")
    }

    fun shutdown() {
        running = false
        try {
            pubSub?.unsubscribe()
            pubSub = null
            subThread?.interrupt()
            subThread?.join(2_000L)
            subThread = null
            if (::pool.isInitialized) {
                pool.close()
            }
        } catch (e: Exception) {
            BlinkLog.warn("Redis 关闭时发生异常: ${e.message}")
        }
    }
}
