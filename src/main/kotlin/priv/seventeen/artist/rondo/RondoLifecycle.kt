package priv.seventeen.artist.rondo

import org.bukkit.Bukkit
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.permissions.Permission
import org.bukkit.permissions.PermissionDefault
import priv.seventeen.artist.blink.BlinkLog
import priv.seventeen.artist.blink.event.AutoListener
import priv.seventeen.artist.blink.lifecycle.Awake
import priv.seventeen.artist.blink.lifecycle.LifeCycle
import priv.seventeen.artist.rondo.account.AccountManager
import priv.seventeen.artist.rondo.command.AdminCommand
import priv.seventeen.artist.rondo.command.MoneyCommand
import priv.seventeen.artist.rondo.config.MainConfig
import priv.seventeen.artist.rondo.config.MessageConfig
import priv.seventeen.artist.rondo.currency.CurrencyRegistry
import priv.seventeen.artist.rondo.exchange.ExchangeManager
import priv.seventeen.artist.rondo.log.LogManager
import priv.seventeen.artist.rondo.placeholder.PAPIHook
import priv.seventeen.artist.rondo.ranking.RankingManager
import priv.seventeen.artist.rondo.redis.RedisManager
import priv.seventeen.artist.rondo.storage.StorageManager
import priv.seventeen.artist.rondo.vault.VaultHook

/**
 * Rondo 生命周期管理
 */
object RondoLifecycle {

    @Awake(LifeCycle.ENABLE, priority = 0)
    fun onEnable() {
        // 加载配置
        MainConfig.load()
        MessageConfig.load()

        // 加载货币
        CurrencyRegistry.loadAll()

        // 初始化存储
        StorageManager.initialize(MainConfig.instance)

        // 跨服模式：初始化 Redis
        if (MainConfig.instance.crossServer.enabled) {
            if (MainConfig.instance.storage.type.lowercase() != "mysql") {
                BlinkLog.warn("跨服模式建议使用 MySQL 存储，当前为 ${MainConfig.instance.storage.type}")
                BlinkLog.warn("流水日志和排行榜数据将无法跨服共享")
            }
            try {
                RedisManager.initialize(MainConfig.instance)
            } catch (e: Exception) {
                BlinkLog.error("Redis 初始化失败，跨服同步已禁用", e)
            }
        }

        // 初始化账户管理
        AccountManager.initialize(MainConfig.instance)

        // 初始化日志管理
        LogManager.initialize(MainConfig.instance)

        // 初始化兑换系统
        ExchangeManager.initialize()

        // 初始化排行榜
        RankingManager.initialize(MainConfig.instance)

        // 注册权限节点（Bukkit 对未注册权限默认按 OP 处理，必须显式注册玩家权限默认值）
        registerPermissions()

        // 注册命令
        MoneyCommand.register()
        AdminCommand.register()

        // 对接 Vault
        if (MainConfig.instance.features.vaultHook) {
            VaultHook.hook()
        }

        // 对接 PlaceholderAPI
        if (MainConfig.instance.features.papiHook) {
            PAPIHook.hook()
        }

        val mode = if (MainConfig.instance.crossServer.enabled && RedisManager.isEnabled) "§b跨服" else "§7单服"
        BlinkLog.success("Rondo 已启用 §7(${CurrencyRegistry.getAll().size} 个货币, $mode§7)")
    }

    /**
     * 注册权限节点及其默认值。
     * Bukkit 对未注册的权限节点默认按 OP 语义处理，若不注册，普通玩家会被
     * BlinkCommand 的 hasPermission 校验拒绝，无法使用 /money 等基础命令。
     */
    private fun registerPermissions() {
        val pm = Bukkit.getPluginManager()
        fun reg(node: String, default: PermissionDefault) {
            if (pm.getPermission(node) == null) {
                pm.addPermission(Permission(node, default))
            }
        }
        reg("rondo.use", PermissionDefault.TRUE)
        reg("rondo.transfer", PermissionDefault.TRUE)
        reg("rondo.exchange", PermissionDefault.TRUE)
        reg("rondo.log", PermissionDefault.TRUE)
        reg("rondo.top", PermissionDefault.TRUE)
        reg("rondo.admin", PermissionDefault.OP)
    }

    @Awake(LifeCycle.DISABLE, priority = 0)
    fun onDisable() {
        LogManager.shutdown()
        AccountManager.shutdown()
        RedisManager.shutdown()
        StorageManager.shutdown()
        VaultHook.unhook()
        PAPIHook.unhook()
        BlinkLog.info("Rondo 已卸载")
    }

    @AutoListener
    fun onPlayerJoin(event: PlayerJoinEvent) {
        AccountManager.loadPlayer(event.player.uniqueId)
    }

    @AutoListener
    fun onPlayerQuit(event: PlayerQuitEvent) {
        AccountManager.unloadPlayer(event.player.uniqueId)
    }
}
