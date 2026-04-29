package priv.seventeen.artist.rondo.command

import org.bukkit.Bukkit
import priv.seventeen.artist.blink.command.BlinkCommand
import priv.seventeen.artist.blink.command.BlinkCommandRegistrar
import priv.seventeen.artist.blink.command.CommandContext
import priv.seventeen.artist.blink.command.SenderType
import priv.seventeen.artist.rondo.Rondo
import priv.seventeen.artist.rondo.api.RondoAPI
import priv.seventeen.artist.rondo.config.MainConfig
import priv.seventeen.artist.rondo.config.MessageConfig
import priv.seventeen.artist.rondo.currency.CurrencyRegistry
import priv.seventeen.artist.rondo.exchange.ExchangeManager
import priv.seventeen.artist.rondo.log.TransactionLog
import priv.seventeen.artist.rondo.ranking.RankingManager
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.*

/**
 * 管理员命令组 /rondo
 */
object AdminCommand {

    private lateinit var messages: MessageConfig
    private lateinit var mainConfig: MainConfig

    fun register(messages: MessageConfig, mainConfig: MainConfig) {
        this.messages = messages
        this.mainConfig = mainConfig

        val cmd = BlinkCommand("rondo")
            .command("give", "发放货币", permission = "rondo.admin",
                args = arrayOf("player", "currency", "amount"), sender = SenderType.ALL) { ctx ->
                handleGive(ctx)
            }
            .command("take", "扣除货币", permission = "rondo.admin",
                args = arrayOf("player", "currency", "amount"), sender = SenderType.ALL) { ctx ->
                handleTake(ctx)
            }
            .command("set", "设置余额", permission = "rondo.admin",
                args = arrayOf("player", "currency", "amount"), sender = SenderType.ALL) { ctx ->
                handleSet(ctx)
            }
            .command("check", "查看玩家余额", permission = "rondo.admin",
                args = arrayOf("player", "?currency"), sender = SenderType.ALL) { ctx ->
                handleCheck(ctx)
            }
            .command("log", "查看玩家流水", permission = "rondo.admin",
                args = arrayOf("player", "?currency", "?page"), sender = SenderType.ALL) { ctx ->
                handleAdminLog(ctx)
            }
            .command("reload", "重载配置", permission = "rondo.admin", sender = SenderType.ALL) { ctx ->
                handleReload(ctx)
            }
            .command("reset", "重置余额", permission = "rondo.admin",
                args = arrayOf("player", "currency"), sender = SenderType.ALL) { ctx ->
                handleReset(ctx)
            }
            .command("help", "查看帮助", permission = "rondo.admin", sender = SenderType.ALL) { ctx ->
                showHelp(ctx)
            }
            .tabComplete("currency") {
                CurrencyRegistry.getIds().toList()
            }

        BlinkCommandRegistrar.register(Rondo.plugin, cmd)
    }

    private fun resolvePlayer(name: String): UUID? {
        // 先查在线
        val online = Bukkit.getPlayer(name)
        if (online != null) return online.uniqueId
        // 再查离线
        @Suppress("DEPRECATION")
        val offline = Bukkit.getOfflinePlayer(name)
        if (offline.hasPlayedBefore()) return offline.uniqueId
        return null
    }

    private fun handleGive(ctx: CommandContext) {
        val playerName = ctx.arg(0)
        val currencyId = ctx.arg(1)
        val amountStr = ctx.arg(2)

        val uuid = resolvePlayer(playerName)
        if (uuid == null) {
            ctx.reply(messages.errorPlayerNotFound.replace("{prefix}", messages.prefix).replace("{name}", playerName))
            return
        }

        val currency = CurrencyRegistry.get(currencyId)
        if (currency == null) {
            ctx.reply(messages.errorCurrencyNotFound.replace("{prefix}", messages.prefix).replace("{id}", currencyId))
            return
        }

        val amount = try { BigDecimal(amountStr) } catch (_: Exception) {
            ctx.reply(messages.errorInvalidAmount.replace("{prefix}", messages.prefix))
            return
        }
        if (amount <= BigDecimal.ZERO) {
            ctx.reply(messages.errorInvalidAmount.replace("{prefix}", messages.prefix))
            return
        }

        val success = RondoAPI.deposit(uuid, currency.id, amount, "admin:give")
        if (success) {
            ctx.reply(messages.adminGive
                .replace("{prefix}", messages.prefix)
                .replace("{player}", playerName)
                .replace("{color}", currency.color)
                .replace("{symbol}", currency.symbol)
                .replace("{display_name}", currency.displayName)
                .replace("{amount}", amount.toPlainString()))
        } else {
            ctx.reply("${messages.prefix}§c操作失败")
        }
    }

    private fun handleTake(ctx: CommandContext) {
        val playerName = ctx.arg(0)
        val currencyId = ctx.arg(1)
        val amountStr = ctx.arg(2)

        val uuid = resolvePlayer(playerName)
        if (uuid == null) {
            ctx.reply(messages.errorPlayerNotFound.replace("{prefix}", messages.prefix).replace("{name}", playerName))
            return
        }

        val currency = CurrencyRegistry.get(currencyId)
        if (currency == null) {
            ctx.reply(messages.errorCurrencyNotFound.replace("{prefix}", messages.prefix).replace("{id}", currencyId))
            return
        }

        val amount = try { BigDecimal(amountStr) } catch (_: Exception) {
            ctx.reply(messages.errorInvalidAmount.replace("{prefix}", messages.prefix))
            return
        }
        if (amount <= BigDecimal.ZERO) {
            ctx.reply(messages.errorInvalidAmount.replace("{prefix}", messages.prefix))
            return
        }

        val success = RondoAPI.withdraw(uuid, currency.id, amount, "admin:take")
        if (success) {
            ctx.reply(messages.adminTake
                .replace("{prefix}", messages.prefix)
                .replace("{player}", playerName)
                .replace("{color}", currency.color)
                .replace("{symbol}", currency.symbol)
                .replace("{display_name}", currency.displayName)
                .replace("{amount}", amount.toPlainString()))
        } else {
            ctx.reply("${messages.prefix}§c操作失败（可能余额不足）")
        }
    }

    private fun handleSet(ctx: CommandContext) {
        val playerName = ctx.arg(0)
        val currencyId = ctx.arg(1)
        val amountStr = ctx.arg(2)

        val uuid = resolvePlayer(playerName)
        if (uuid == null) {
            ctx.reply(messages.errorPlayerNotFound.replace("{prefix}", messages.prefix).replace("{name}", playerName))
            return
        }

        val currency = CurrencyRegistry.get(currencyId)
        if (currency == null) {
            ctx.reply(messages.errorCurrencyNotFound.replace("{prefix}", messages.prefix).replace("{id}", currencyId))
            return
        }

        val amount = try { BigDecimal(amountStr) } catch (_: Exception) {
            ctx.reply(messages.errorInvalidAmount.replace("{prefix}", messages.prefix))
            return
        }

        val success = RondoAPI.setBalance(uuid, currency.id, amount, "admin:set")
        if (success) {
            ctx.reply(messages.adminSet
                .replace("{prefix}", messages.prefix)
                .replace("{player}", playerName)
                .replace("{color}", currency.color)
                .replace("{symbol}", currency.symbol)
                .replace("{display_name}", currency.displayName)
                .replace("{amount}", amount.toPlainString()))
        } else {
            ctx.reply("${messages.prefix}§c操作失败")
        }
    }

    private fun handleCheck(ctx: CommandContext) {
        val playerName = ctx.arg(0)
        val currencyId = if (ctx.arg(1).isNotEmpty()) ctx.arg(1) else null

        val uuid = resolvePlayer(playerName)
        if (uuid == null) {
            ctx.reply(messages.errorPlayerNotFound.replace("{prefix}", messages.prefix).replace("{name}", playerName))
            return
        }

        if (currencyId != null) {
            val currency = CurrencyRegistry.get(currencyId)
            if (currency == null) {
                ctx.reply(messages.errorCurrencyNotFound.replace("{prefix}", messages.prefix).replace("{id}", currencyId))
                return
            }
            val balance = RondoAPI.getBalance(uuid, currency.id)
            ctx.reply(messages.balanceSingle
                .replace("{prefix}", messages.prefix)
                .replace("{color}", currency.color)
                .replace("{symbol}", currency.symbol)
                .replace("{display_name}", currency.displayName)
                .replace("{balance}", balance.setScale(currency.decimalPlaces).toPlainString()))
        } else {
            ctx.reply(messages.adminCheckHeader.replace("{player}", playerName))
            for (currency in CurrencyRegistry.getAll()) {
                val balance = RondoAPI.getBalance(uuid, currency.id)
                val msg = messages.balanceEntry
                    .replace("{color}", currency.color)
                    .replace("{symbol}", currency.symbol)
                    .replace("{display_name}", currency.displayName)
                    .replace("{balance}", balance.setScale(currency.decimalPlaces).toPlainString())
                ctx.reply(msg)
            }
            ctx.reply(messages.balanceFooter)
        }
    }

    private fun handleAdminLog(ctx: CommandContext) {
        val playerName = ctx.arg(0)
        val currencyId = if (ctx.arg(1).isNotEmpty() && ctx.arg(1) != "all") ctx.arg(1) else null
        val page = try { if (ctx.arg(2).isNotEmpty()) ctx.argInt(2) else 1 } catch (_: Exception) { 1 }

        val uuid = resolvePlayer(playerName)
        if (uuid == null) {
            ctx.reply(messages.errorPlayerNotFound.replace("{prefix}", messages.prefix).replace("{name}", playerName))
            return
        }

        val logs = RondoAPI.getLog(uuid, currencyId, page)
        if (logs.isEmpty()) {
            ctx.reply(messages.logEmpty.replace("{prefix}", messages.prefix))
            return
        }

        ctx.reply(messages.logHeader)
        val sdf = SimpleDateFormat("MM-dd HH:mm")
        for (log in logs) {
            val currency = CurrencyRegistry.get(log.currencyId)
            val actionStr = when (log.action) {
                TransactionLog.Action.DEPOSIT -> "§a+"
                TransactionLog.Action.WITHDRAW -> "§c-"
                TransactionLog.Action.TRANSFER_IN -> "§a←"
                TransactionLog.Action.TRANSFER_OUT -> "§c→"
                TransactionLog.Action.SET -> "§e="
                TransactionLog.Action.EXCHANGE_IN -> "§a⇐"
                TransactionLog.Action.EXCHANGE_OUT -> "§c⇒"
            }
            val msg = messages.logEntry
                .replace("{time}", sdf.format(Date(log.timestamp)))
                .replace("{color}", currency?.color ?: "§f")
                .replace("{action}", actionStr)
                .replace("{symbol}", currency?.symbol ?: "$")
                .replace("{amount}", log.amount.toPlainString())
                .replace("{source}", log.source)
            ctx.reply(msg)
        }
        ctx.reply(messages.logFooter)
    }

    private fun handleReload(ctx: CommandContext) {
        mainConfig.reload()
        messages.reload()
        CurrencyRegistry.reload()
        ExchangeManager.reload()
        // 异步刷新排行榜
        Bukkit.getScheduler().runTaskAsynchronously(Rondo.plugin, Runnable {
            RankingManager.refreshAll()
        })
        ctx.reply(messages.reloadSuccess.replace("{prefix}", messages.prefix))
    }

    private fun handleReset(ctx: CommandContext) {
        val playerName = ctx.arg(0)
        val currencyId = ctx.arg(1)

        val uuid = resolvePlayer(playerName)
        if (uuid == null) {
            ctx.reply(messages.errorPlayerNotFound.replace("{prefix}", messages.prefix).replace("{name}", playerName))
            return
        }

        val currency = CurrencyRegistry.get(currencyId)
        if (currency == null) {
            ctx.reply(messages.errorCurrencyNotFound.replace("{prefix}", messages.prefix).replace("{id}", currencyId))
            return
        }

        val success = RondoAPI.setBalance(uuid, currency.id, currency.defaultBalance, "admin:reset")
        if (success) {
            ctx.reply(messages.adminSet
                .replace("{prefix}", messages.prefix)
                .replace("{player}", playerName)
                .replace("{color}", currency.color)
                .replace("{symbol}", currency.symbol)
                .replace("{display_name}", currency.displayName)
                .replace("{amount}", currency.defaultBalance.toPlainString()))
        } else {
            ctx.reply("${messages.prefix}§c操作失败")
        }
    }

    private fun showHelp(ctx: CommandContext) {
        ctx.reply("")
        ctx.reply("§8 ┌─────────────────────────────┐")
        ctx.reply("§8 │  §6§l✦ §e§lRondo Admin §7帮助          §8│")
        ctx.reply("§8 ├─────────────────────────────┤")
        ctx.reply("§8 │  §e/rondo give §8......... §7发放货币§8│")
        ctx.reply("§8 │  §e/rondo take §8......... §7扣除货币§8│")
        ctx.reply("§8 │  §e/rondo set §8.......... §7设置余额§8│")
        ctx.reply("§8 │  §e/rondo check §8........ §7查看余额§8│")
        ctx.reply("§8 │  §e/rondo log §8.......... §7查看流水§8│")
        ctx.reply("§8 │  §e/rondo reset §8........ §7重置余额§8│")
        ctx.reply("§8 │  §e/rondo reload §8....... §7重载配置§8│")
        ctx.reply("§8 ├─────────────────────────────┤")
        ctx.reply("§8 │  §7格式: §f/rondo <cmd> <玩家> <货币> [量]§8│")
        ctx.reply("§8 │  §7提示: §f支持离线玩家操作§8          │")
        ctx.reply("§8 └─────────────────────────────┘")
    }
}
