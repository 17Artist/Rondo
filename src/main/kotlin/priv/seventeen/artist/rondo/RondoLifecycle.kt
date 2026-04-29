package priv.seventeen.artist.rondo

import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
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
import priv.seventeen.artist.rondo.storage.StorageManager
import priv.seventeen.artist.rondo.vault.VaultHook

/**
 * Rondo 生命周期管理
 */
object RondoLifecycle {

    private lateinit var mainConfig: MainConfig
    private lateinit var messageConfig: MessageConfig

    @Awake(LifeCycle.ENABLE, priority = 0)
    fun onEnable() {
        BlinkLog.info("§6Rondo §7- Universal Multi-Currency Economy System")
        BlinkLog.info("§7Initializing...")

        // 加载配置
        mainConfig = MainConfig()
        mainConfig.load()

        messageConfig = MessageConfig()
        messageConfig.load()

        // 加载货币
        CurrencyRegistry.loadAll()

        // 初始化存储
        StorageManager.initialize(mainConfig)

        // 初始化账户管理
        AccountManager.initialize(mainConfig)

        // 初始化日志管理
        LogManager.initialize(mainConfig)

        // 初始化兑换系统
        ExchangeManager.initialize()

        // 初始化排行榜
        RankingManager.initialize(mainConfig)

        // 注册命令
        MoneyCommand.register(messageConfig)
        AdminCommand.register(messageConfig, mainConfig)

        // 对接 Vault
        if (mainConfig.features.vaultHook) {
            VaultHook.hook()
        }

        // 对接 PlaceholderAPI
        if (mainConfig.features.papiHook) {
            PAPIHook.hook()
        }

        BlinkLog.info("§aRondo enabled! §7(${CurrencyRegistry.getAll().size} currencies loaded)")
    }

    @Awake(LifeCycle.DISABLE, priority = 0)
    fun onDisable() {
        BlinkLog.info("§7Shutting down Rondo...")

        // 保存所有数据
        LogManager.shutdown()
        AccountManager.shutdown()
        StorageManager.shutdown()

        // 注销集成
        VaultHook.unhook()
        PAPIHook.unhook()

        BlinkLog.info("§cRondo disabled.")
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
