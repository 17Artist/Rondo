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
        when (type) {
            "mysql" -> {
                val mysql = MySQLProvider(config)
                try {
                    mysql.initialize()
                    provider = mysql
                    BlinkLog.success("MySQL 已连接")
                } catch (e: Exception) {
                    mysql.shutdown()
                    throw IllegalStateException("MySQL 初始化失败；为防止写入错误数据源，Rondo 已拒绝启动", e)
                }
            }
            "sqlite" -> {
                if (config.crossServer.enabled) {
                    error("跨服模式必须使用共享 MySQL，不能使用 SQLite")
                }
                val sqlite = SQLiteProvider()
                sqlite.initialize()
                provider = sqlite
                BlinkLog.info("存储类型: SQLite")
            }
            else -> error("不支持的存储类型 '${config.storage.type}'，可选值为 sqlite/mysql")
        }
    }

    fun shutdown() {
        if (::provider.isInitialized) {
            provider.shutdown()
        }
    }
}
