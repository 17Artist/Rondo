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

package priv.seventeen.artist.rondo

import org.bukkit.Bukkit
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.permissions.Permission
import org.bukkit.permissions.PermissionDefault
import priv.seventeen.artist.blink.BlinkLog
import priv.seventeen.artist.blink.bukkitPlugin
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
        try {
            // 加载配置
            MainConfig.load()
            MessageConfig.load()

            // 加载货币
            CurrencyRegistry.loadAll()

            // 初始化存储
            StorageManager.initialize(MainConfig.instance)

            // 跨服模式：初始化 Redis
            if (MainConfig.instance.crossServer.enabled) {
                // 跨服配置采用 fail-closed：共享 MySQL 或 Redis 初始化失败时拒绝启动，
                // 避免某个子服悄悄进入本地写入路径造成资金分叉。
                RedisManager.initialize(MainConfig.instance)
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

            // 兼容运行中热加载插件的场景，不能只依赖后续 PlayerJoinEvent。
            Bukkit.getOnlinePlayers().forEach { player ->
                AccountManager.loadPlayer(player.uniqueId, player.name)
            }

            val mode = if (MainConfig.instance.crossServer.enabled && RedisManager.isEnabled) {
                "§b跨服"
            } else {
                "§7单服"
            }
            BlinkLog.success(
                "[startup-complete] Rondo 已启用 §7(${CurrencyRegistry.getAll().size} 个货币, $mode§7)"
            )
        } catch (failure: Throwable) {
            BlinkLog.error("[startup-failed] Rondo 初始化失败，正在关闭已创建的资源", failure)
            cleanup()
            try {
                Bukkit.getPluginManager().disablePlugin(bukkitPlugin)
            } catch (disableFailure: Throwable) {
                failure.addSuppressed(disableFailure)
            }
            throw failure
        }
    }

    /**
     * 注册权限节点及其默认值。
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
        cleanup()
        BlinkLog.info("Rondo 已卸载")
    }

    private fun cleanup() {
        VaultHook.unhook()
        PAPIHook.unhook()
        AccountManager.shutdown()
        LogManager.shutdown()
        RedisManager.shutdown()
        StorageManager.shutdown()
    }

    @AutoListener
    fun onPlayerJoin(event: PlayerJoinEvent) {
        AccountManager.loadPlayer(event.player.uniqueId, event.player.name)
    }

    @AutoListener
    fun onPlayerQuit(event: PlayerQuitEvent) {
        AccountManager.unloadPlayer(event.player.uniqueId)
    }
}
