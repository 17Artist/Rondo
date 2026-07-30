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

package priv.seventeen.artist.rondo.config

import priv.seventeen.artist.blink.bukkitPlugin
import priv.seventeen.artist.blink.config.BlinkConfig
import priv.seventeen.artist.blink.config.BlinkSection
import priv.seventeen.artist.blink.config.Comment
import priv.seventeen.artist.blink.config.ConfigKey

/**
 * 主配置文件
 */
class MainConfig : BlinkConfig(bukkitPlugin, "config") {

    @Comment("数据库配置")
    var storage: StorageSection = StorageSection()

    @Comment("性能配置")
    var performance: PerformanceSection = PerformanceSection()

    @Comment("功能开关")
    var features: FeaturesSection = FeaturesSection()

    @Comment("跨服同步 (Redis)")
    @ConfigKey("cross-server")
    var crossServer: CrossServerSection = CrossServerSection()

    companion object {
        lateinit var instance: MainConfig private set
        fun load() {
            val candidate = MainConfig()
            candidate.load()
            candidate.validate()
            instance = candidate
        }

        fun reload() = load()
    }

    private fun validate() {
        require(storage.type.lowercase() in setOf("sqlite", "mysql")) {
            "storage.type 只能为 sqlite 或 mysql"
        }
        if (storage.type.equals("mysql", ignoreCase = true)) {
            require(storage.mysql.port in 1..65535) { "storage.mysql.port 必须在 1..65535 之间" }
            require(storage.mysql.host.isNotBlank()) { "storage.mysql.host 不能为空" }
            require(storage.mysql.username.isNotBlank()) { "storage.mysql.username 不能为空" }
            require(storage.mysql.poolSize in 1..128) { "storage.mysql.pool-size 必须在 1..128 之间" }
            require(storage.mysql.database.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
                "storage.mysql.database 只能包含字母、数字、下划线或连字符"
            }
            require(storage.mysql.sslMode.uppercase() in setOf(
                "DISABLED", "PREFERRED", "REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY"
            )) { "storage.mysql.ssl-mode 值无效" }
        }

        require(performance.logQueueSize in 100..100_000) {
            "performance.log-queue-size 必须在 100..100000 之间"
        }
        require(performance.rankingRefresh >= 200) {
            "performance.ranking-refresh 不能小于 200 tick"
        }
        require(performance.rankingSize in 1..10_000) {
            "performance.ranking-size 必须在 1..10000 之间"
        }
        require(performance.readCacheOfflineTtlSeconds in 0..86_400) {
            "performance.read-cache-offline-ttl-seconds 必须在 0..86400 之间"
        }
        require(performance.readCacheMaxOfflineEntries in 1..1_000_000) {
            "performance.read-cache-max-offline-entries 必须在 1..1000000 之间"
        }
        require(features.logRetentionDays == -1 || features.logRetentionDays > 0) {
            "features.log-retention-days 只能为 -1 或正整数"
        }

        if (crossServer.enabled) {
            require(storage.type.equals("mysql", ignoreCase = true)) {
                "跨服模式必须配置 storage.type=mysql"
            }
            require(crossServer.redis.port in 1..65535) {
                "cross-server.redis.port 必须在 1..65535 之间"
            }
            require(crossServer.redis.host.isNotBlank()) { "cross-server.redis.host 不能为空" }
            require(crossServer.redis.database in 0..15) {
                "cross-server.redis.database 必须在 0..15 之间"
            }
            require(crossServer.redis.poolSize in 2..128) {
                "cross-server.redis.pool-size 必须在 2..128 之间"
            }
            require(crossServer.redis.timeoutMillis in 500..30_000) {
                "cross-server.redis.timeout-millis 必须在 500..30000 之间"
            }
            require(crossServer.syncChannel.matches(Regex("[A-Za-z0-9:_-]{1,128}"))) {
                "cross-server.sync-channel 格式无效"
            }
        }
    }
}

class StorageSection : BlinkSection() {
    @Comment("存储类型: sqlite / mysql")
    var type: String = "sqlite"

    @Comment("MySQL 配置")
    var mysql: MySQLSection = MySQLSection()
}

class MySQLSection : BlinkSection() {
    var host: String = "localhost"
    var port: Int = 3306
    var database: String = "rondo"
    var username: String = "rondo"
    var password: String = ""

    @ConfigKey("ssl-mode")
    @Comment("TLS 模式: DISABLED / PREFERRED / REQUIRED / VERIFY_CA / VERIFY_IDENTITY")
    var sslMode: String = "PREFERRED"

    @ConfigKey("allow-public-key-retrieval")
    @Comment("是否允许非 TLS RSA 公钥检索；生产环境建议保持 false")
    var allowPublicKeyRetrieval: Boolean = false

    @ConfigKey("pool-size")
    @Comment("连接池大小")
    var poolSize: Int = 10
}

class PerformanceSection : BlinkSection() {
    @ConfigKey("log-queue-size")
    @Comment("日志队列大小")
    var logQueueSize: Int = 1000

    @ConfigKey("ranking-refresh")
    @Comment("排行榜刷新间隔 (tick, 6000=5分钟)")
    var rankingRefresh: Int = 6000

    @ConfigKey("ranking-size")
    @Comment("排行榜保留条数")
    var rankingSize: Int = 100

    @ConfigKey("read-cache-offline-ttl-seconds")
    @Comment("玩家离线后只读经济快照保留秒数 (0=立即清除)")
    var readCacheOfflineTtlSeconds: Int = 300

    @ConfigKey("read-cache-max-offline-entries")
    @Comment("最多保留的离线只读经济快照数量（在线玩家不计入）")
    var readCacheMaxOfflineEntries: Int = 10_000
}

class FeaturesSection : BlinkSection() {
    @ConfigKey("vault-hook")
    @Comment("是否对接 Vault")
    var vaultHook: Boolean = true

    @ConfigKey("papi-hook")
    @Comment("是否对接 PlaceholderAPI")
    var papiHook: Boolean = true

    @ConfigKey("transaction-log")
    @Comment("是否记录流水日志")
    var transactionLog: Boolean = true

    @ConfigKey("log-retention-days")
    @Comment("日志保留天数 (-1=永久)")
    var logRetentionDays: Int = 30
}

class CrossServerSection : BlinkSection() {
    @Comment("是否启用跨服同步")
    var enabled: Boolean = false

    @Comment("Redis 配置")
    var redis: RedisSection = RedisSection()

    @ConfigKey("sync-channel")
    @Comment("Pub/Sub 通道名")
    var syncChannel: String = "rondo:sync"
}

class RedisSection : BlinkSection() {
    var host: String = "localhost"
    var port: Int = 6379
    var username: String = ""
    var password: String = ""
    var database: Int = 0
    var ssl: Boolean = false

    @ConfigKey("timeout-millis")
    @Comment("连接和读取超时（毫秒）")
    var timeoutMillis: Int = 3000

    @ConfigKey("pool-size")
    @Comment("连接池大小")
    var poolSize: Int = 8
}
