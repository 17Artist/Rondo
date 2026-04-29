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

    companion object {
        lateinit var instance: MainConfig private set
        fun load() { instance = MainConfig(); instance.load() }
        fun reload() = instance.reload()
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
    var username: String = "root"
    var password: String = ""

    @ConfigKey("pool-size")
    @Comment("连接池大小")
    var poolSize: Int = 10
}

class PerformanceSection : BlinkSection() {
    @ConfigKey("save-interval")
    @Comment("批量保存间隔 (tick, 100=5秒)")
    var saveInterval: Int = 100

    @ConfigKey("log-queue-size")
    @Comment("日志队列大小")
    var logQueueSize: Int = 1000

    @ConfigKey("ranking-refresh")
    @Comment("排行榜刷新间隔 (tick, 6000=5分钟)")
    var rankingRefresh: Int = 6000

    @ConfigKey("ranking-size")
    @Comment("排行榜保留条数")
    var rankingSize: Int = 100
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
