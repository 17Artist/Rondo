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
import redis.clients.jedis.JedisPubSub
import java.util.UUID

/**
 * Redis Pub/Sub 消息接收器 — 收到其他服务端的余额变更通知后刷新本地缓存
 *
 * 消息格式: {serverId}:{uuid}:{currencyId}
 */
class RedisSyncListener(
    private val subscribedCallback: () -> Unit = {}
) : JedisPubSub() {

    override fun onSubscribe(channel: String, subscribedChannels: Int) {
        subscribedCallback()
    }

    override fun onMessage(channel: String, message: String) {
        try {
            val parts = message.split(":", limit = 3)
            if (parts.size != 3) return

            val serverId = parts[0]
            // 忽略本服自己发的消息（本服已在 refreshLocalCache 中同步更新）
            if (serverId == RedisManager.serverId) return

            val uuid = try { UUID.fromString(parts[1]) } catch (_: Exception) { return }
            val currencyId = parts[2]

            // 共享 MySQL 是事实源；消息只负责让其他子服刷新在线缓存。
            AccountManager.refreshFromStorage(uuid, currencyId)
        } catch (e: Exception) {
            // 订阅线程不能因单条坏消息或短暂数据库错误退出。
            BlinkLog.warn("处理 Redis 同步通知失败: ${e.message}")
        }
    }
}
