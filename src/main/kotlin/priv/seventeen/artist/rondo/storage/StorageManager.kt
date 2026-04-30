package priv.seventeen.artist.rondo.storage

import priv.seventeen.artist.blink.BlinkLog
import priv.seventeen.artist.rondo.config.MainConfig

/**
 * 存储管理器 — 根据配置创建对应的存储提供者
 */
object StorageManager {

    lateinit var provider: StorageProvider
        private set

    fun initialize(config: MainConfig) {
        val type = config.storage.type.lowercase()
        if (type == "mysql") {
            try {
                val mysql = MySQLProvider(config)
                mysql.initialize()
                provider = mysql
                BlinkLog.success("MySQL 已连接")
                return
            } catch (e: Exception) {
                BlinkLog.warn("MySQL 连接失败，回退到 SQLite: ${e.message}")
            }
        }
        val sqlite = SQLiteProvider()
        sqlite.initialize()
        provider = sqlite
        BlinkLog.info("存储类型: SQLite")
    }

    fun shutdown() {
        if (::provider.isInitialized) {
            provider.shutdown()
        }
    }
}
