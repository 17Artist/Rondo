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
        provider = when (config.storage.type.lowercase()) {
            "mysql" -> MySQLProvider(config)
            else -> SQLiteProvider()
        }
        provider.initialize()
        BlinkLog.info("Storage type: ${config.storage.type}")
    }

    fun shutdown() {
        if (::provider.isInitialized) {
            provider.shutdown()
        }
    }
}
