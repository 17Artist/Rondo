package priv.seventeen.artist.rondo.redis

import org.bukkit.scheduler.BukkitRunnable
import priv.seventeen.artist.blink.bukkitPlugin
import priv.seventeen.artist.rondo.account.AccountManager
import redis.clients.jedis.JedisPubSub
import java.util.UUID

/**
 * Redis Pub/Sub 消息接收器 — 收到其他服务端的余额变更通知后刷新本地缓存
 *
 * 消息格式: {serverId}:{uuid}:{currencyId}
 */
class RedisSyncListener : JedisPubSub() {

    override fun onMessage(channel: String, message: String) {
        try {
            val parts = message.split(":", limit = 3)
            if (parts.size != 3) return

            val serverId = parts[0]
            // 忽略本服自己发的消息（本服已在 refreshLocalCache 中同步更新）
            if (serverId == RedisManager.serverId) return

            val uuid = try { UUID.fromString(parts[1]) } catch (_: Exception) { return }
            val currencyId = parts[2]

            // 检查该玩家是否在本服在线
            val account = AccountManager.getAccount(uuid) ?: return

            // 从 Redis 读取最新余额，在主线程更新内存缓存
            val balanceData = RedisEconomyProvider.getBalanceData(uuid, currencyId)

            object : BukkitRunnable() {
                override fun run() {
                    account.updateFromRedis(currencyId, balanceData)
                }
            }.runTask(bukkitPlugin)
        } catch (_: Exception) {
            // 忽略解析错误，不影响订阅
        }
    }
}
