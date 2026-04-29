package priv.seventeen.artist.rondo.command

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import priv.seventeen.artist.blink.command.BlinkCommand
import priv.seventeen.artist.blink.command.BlinkCommandRegistrar
import priv.seventeen.artist.blink.command.CommandContext
import priv.seventeen.artist.blink.command.SenderType
import priv.seventeen.artist.rondo.Rondo
import priv.seventeen.artist.rondo.api.RondoAPI
import priv.seventeen.artist.rondo.config.MessageConfig
import priv.seventeen.artist.rondo.currency.CurrencyRegistry
import priv.seventeen.artist.rondo.exchange.ExchangeManager
import priv.seventeen.artist.rondo.log.TransactionLog
import priv.seventeen.artist.rondo.ranking.RankingManager
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.*

/**
 * 玩家命令组 /money
 */
object MoneyCommand {

    private lateinit var messages: MessageConfig

    fun register(messages: MessageConfig) {
        this.messages = messages

        val cmd = BlinkCommand("money")
            .command("", "查看所有货币余额", sender = SenderType.PLAYER) { ctx ->
                showAllBalances(ctx)
            }
            .command("help", "查看帮助", sender = SenderType.ALL) { ctx ->
                showHelp(ctx)
            }
            .command("pay", "转账给其他玩家", permission = "rondo.transfer",
                args = arrayOf("player", "currency", "amount"), sender = SenderType.PLAYER) { ctx ->
                handleTransfer(ctx)
            }
            .command("exchange", "货币兑换", permission = "rondo.exchange",
                args = arrayOf("from", "to", "amount"), sender = SenderType.PLAYER) { ctx ->
                handleExchange(ctx)
            }
            .command("log", "查看交易记录", permission = "rondo.log",
                args = arrayOf("?currency", "?page"), sender = SenderType.PLAYER) { ctx ->
                handleLog(ctx)
            }
            .command("top", "查看排行榜", permission = "rondo.top",
                args = arrayOf("currency", "?page"), sender = SenderType.ALL) { ctx ->
                handleTop(ctx)
            }
            .tabComplete("currency") {
                CurrencyRegistry.getIds().toList()
            }
            .tabComplete("from") {
                CurrencyRegistry.getIds().toList()
            }
            .tabComplete("to") {
                CurrencyRegistry.getIds().toList()
            }

        BlinkCommandRegistrar.register(Rondo.plugin, cmd)
    }

    private fun showAllBalances(ctx: CommandContext) {
        val player = ctx.sender as Player
        ctx.reply(messages.balanceHeader)
        for (currency in CurrencyRegistry.getAll()) {
            val balance = RondoAPI.getBalance(player.uniqueId, currency.id)
            val msg = messages.balanceEntry
                .replace("{color}", currency.color)
                .replace("{symbol}", currency.symbol)
                .replace("{display_name}", currency.displayName)
                .replace("{balance}", balance.setScale(currency.decimalPlaces).toPlainString())
            ctx.reply(msg)
        }
        ctx.reply(messages.balanceFooter)
    }

    private fun handleTransfer(ctx: CommandContext) {
        val player = ctx.sender as Player
        val targetName = ctx.arg(0)
        val currencyId = ctx.arg(1)
        val amountStr = ctx.arg(2)

        @Suppress("DEPRECATION")
        val target = Bukkit.getOfflinePlayer(targetName)
        if (!target.hasPlayedBefore() && !target.isOnline) {
            ctx.reply(messages.errorPlayerNotFound.replace("{prefix}", messages.prefix).replace("{name}", targetName))
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

        val result = RondoAPI.transfer(player.uniqueId, target.uniqueId, currencyId, amount)
        if (result.success) {
            val msg = messages.transferSuccess
                .replace("{prefix}", messages.prefix)
                .replace("{target}", target.name ?: targetName)
                .replace("{color}", currency.color)
                .replace("{symbol}", currency.symbol)
                .replace("{amount}", amount.toPlainString())
                .replace("{tax}", result.taxAmount.toPlainString())
            ctx.reply(msg)

            // 通知接收方
            val targetPlayer = Bukkit.getPlayer(target.uniqueId)
            if (targetPlayer != null) {
                val receivedMsg = messages.transferReceived
                    .replace("{prefix}", messages.prefix)
                    .replace("{sender}", player.name)
                    .replace("{color}", currency.color)
                    .replace("{symbol}", currency.symbol)
                    .replace("{amount}", amount.toPlainString())
                targetPlayer.sendMessage(receivedMsg)
            }
        } else {
            when {
                result.message.contains("余额") -> ctx.reply(messages.transferInsufficient.replace("{prefix}", messages.prefix))
                result.message.contains("转账") -> ctx.reply(messages.transferNotTransferable.replace("{prefix}", messages.prefix))
                else -> ctx.reply("${messages.prefix}§c${result.message}")
            }
        }
    }

    private fun handleExchange(ctx: CommandContext) {
        val player = ctx.sender as Player
        val fromId = ctx.arg(0)
        val toId = ctx.arg(1)
        val amountStr = ctx.arg(2)

        val amount = try { BigDecimal(amountStr) } catch (_: Exception) {
            ctx.reply(messages.errorInvalidAmount.replace("{prefix}", messages.prefix))
            return
        }

        val rule = ExchangeManager.findRule(fromId, toId)
        if (rule == null) {
            ctx.reply("${messages.prefix}§c未找到从 $fromId 到 $toId 的兑换规则")
            return
        }

        val result = ExchangeManager.execute(player.uniqueId, rule.id, amount)
        if (result.success) {
            val fromCurrency = CurrencyRegistry.get(fromId) ?: return
            val toCurrency = CurrencyRegistry.get(toId) ?: return
            val msg = messages.exchangeSuccess
                .replace("{prefix}", messages.prefix)
                .replace("{from_color}", fromCurrency.color)
                .replace("{from_symbol}", fromCurrency.symbol)
                .replace("{from_amount}", result.fromAmount.toPlainString())
                .replace("{to_color}", toCurrency.color)
                .replace("{to_symbol}", toCurrency.symbol)
                .replace("{to_amount}", result.toAmount.toPlainString())
            ctx.reply(msg)
        } else {
            if (result.message.contains("上限")) {
                ctx.reply(messages.exchangeLimitReached.replace("{prefix}", messages.prefix))
            } else if (result.message.contains("余额")) {
                val fromCurrency = CurrencyRegistry.get(fromId)
                ctx.reply(messages.exchangeInsufficient
                    .replace("{prefix}", messages.prefix)
                    .replace("{color}", fromCurrency?.color ?: "")
                    .replace("{symbol}", fromCurrency?.symbol ?: "")
                    .replace("{amount}", result.fromAmount.toPlainString()))
            } else {
                ctx.reply("${messages.prefix}§c${result.message}")
            }
        }
    }

    private fun handleLog(ctx: CommandContext) {
        val player = ctx.sender as Player
        val arg0 = ctx.arg(0)
        val arg1 = ctx.arg(1)

        // 解析参数：/money log [货币|页码] [页码]
        val currencyId: String?
        val page: Int

        if (arg0.isEmpty()) {
            currencyId = null
            page = 1
        } else if (arg0 == "all") {
            currencyId = null
            page = arg1.toIntOrNull() ?: 1
        } else if (arg0.toIntOrNull() != null) {
            // 第一个参数是数字，当作页码
            currencyId = null
            page = arg0.toInt()
        } else {
            // 第一个参数是货币 ID
            currencyId = arg0
            page = arg1.toIntOrNull() ?: 1
        }

        val logs = RondoAPI.getLog(player.uniqueId, currencyId, page)
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

    private fun handleTop(ctx: CommandContext) {
        val currencyId = ctx.arg(0)
        val page = try { if (ctx.arg(1).isNotEmpty()) ctx.argInt(1) else 1 } catch (_: Exception) { 1 }

        val currency = CurrencyRegistry.get(currencyId)
        if (currency == null) {
            ctx.reply(messages.errorCurrencyNotFound.replace("{prefix}", messages.prefix).replace("{id}", currencyId))
            return
        }

        val entries = RondoAPI.getRanking(currencyId, page)
        if (entries.isEmpty()) {
            ctx.reply("${messages.prefix}§7暂无排行数据")
            return
        }

        ctx.reply(messages.rankingHeader.replace("{display_name}", currency.displayName))
        for (entry in entries) {
            val msg = messages.rankingEntry
                .replace("{rank}", entry.rank.toString())
                .replace("{player}", entry.playerName)
                .replace("{color}", currency.color)
                .replace("{symbol}", currency.symbol)
                .replace("{balance}", entry.balance.setScale(currency.decimalPlaces).toPlainString())
            ctx.reply(msg)
        }

        val totalPages = RankingManager.getTotalPages(currencyId, 10)
        val myRank = if (ctx.sender is Player) {
            RondoAPI.getPlayerRank((ctx.sender as Player).uniqueId, currencyId) ?: 0
        } else 0

        ctx.reply(messages.rankingFooter
            .replace("{page}", page.toString())
            .replace("{total_pages}", totalPages.toString())
            .replace("{my_rank}", if (myRank > 0) myRank.toString() else "未上榜"))
    }

    private fun showHelp(ctx: CommandContext) {
        ctx.reply("")
        ctx.reply("§8 ┌─────────────────────────────┐")
        ctx.reply("§8 │  §6§l✦ §e§lRondo §7帮助                §8│")
        ctx.reply("§8 ├─────────────────────────────┤")
        ctx.reply("§8 │  §e/money §8.............. §7查看余额§8  │")
        ctx.reply("§8 │  §e/money pay §8.......... §7转账§8    │")
        ctx.reply("§8 │  §e/money exchange §8..... §7兑换§8    │")
        ctx.reply("§8 │  §e/money log §8.......... §7流水§8    │")
        ctx.reply("§8 │  §e/money top §8.......... §7排行榜§8  │")
        ctx.reply("§8 ├─────────────────────────────┤")
        ctx.reply("§8 │  §7用法: §f/money pay <玩家> <货币> <数量>§8│")
        ctx.reply("§8 │  §7用法: §f/money exchange <源> <目标> <量>§8│")
        ctx.reply("§8 └─────────────────────────────┘")
    }
}
