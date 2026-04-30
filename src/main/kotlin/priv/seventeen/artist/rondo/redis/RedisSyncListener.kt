package priv.seventeen.artist.rondo.redis

import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitRunnable
import priv.seventeen.artist.blink.bukkitPlugin
import priv.seventeen.artist.rondo.account.AccountManager
import redis.clients.jedis.JedisPubSub
import java.util.UUID

/**
 * Redis Pub/Sub 消息接收器 — 收到其他服务端的余额变更通知后刷新本地缓存
 *
 * 消息格式: {uuid}:{currencyId}
 */
class RedisSyncListener : JedisPubSub() {

    override fun onMessage(channel: String, message: String) {
        try {
            val parts = message.split(":", limit = 2)
            if (parts.size != 2) return

            val uuid = try { UUID.fromString(parts[0]) } catch (_: Exception) { return }
            val currencyId = parts[1]

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
