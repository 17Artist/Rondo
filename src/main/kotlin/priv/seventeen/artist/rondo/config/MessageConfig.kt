package priv.seventeen.artist.rondo.config

import priv.seventeen.artist.blink.bukkitPlugin
import priv.seventeen.artist.blink.config.BlinkConfig
import priv.seventeen.artist.blink.config.Comment
import priv.seventeen.artist.blink.config.ConfigKey

/**
 * 消息配置文件
 */
class MessageConfig : BlinkConfig(bukkitPlugin, "messages") {

    @Comment("前缀")
    var prefix: String = "§7[§6§lR§7] "

    @Comment("余额查看")
    @ConfigKey("balance-header")
    var balanceHeader: String = "\n§8 ┌─────────────────────────────┐\n§8 │  §6§l✦ §e§l我的钱包                  §8│"

    @ConfigKey("balance-entry")
    var balanceEntry: String = "§8 │  {color}  {display_name}§8: §f{balance} {color}{symbol}§8  │"

    @ConfigKey("balance-footer")
    var balanceFooter: String = "§8 └─────────────────────────────┘"

    @ConfigKey("balance-single")
    var balanceSingle: String = "{prefix}{color}{display_name}§8 » §f{balance} {color}{symbol}"

    @Comment("转账")
    @ConfigKey("transfer-success")
    var transferSuccess: String = "{prefix}§a\u2714 §f向 §e{target} §f转账 {color}{symbol}{amount}§f，税 {color}{symbol}{tax}"

    @ConfigKey("transfer-received")
    var transferReceived: String = "{prefix}§a\u2709 §f收到 §e{sender} §f的转账 {color}{symbol}{amount}"

    @ConfigKey("transfer-not-transferable")
    var transferNotTransferable: String = "{prefix}§c\u2716 该货币不支持转账"

    @ConfigKey("transfer-insufficient")
    var transferInsufficient: String = "{prefix}§c\u2716 余额不足，无法完成转账"

    @Comment("兑换")
    @ConfigKey("exchange-success")
    var exchangeSuccess: String = "{prefix}§a\u2714 §f兑换完成 {from_color}{from_symbol}{from_amount} §7\u279c {to_color}{to_symbol}{to_amount}"

    @ConfigKey("exchange-limit-reached")
    var exchangeLimitReached: String = "{prefix}§c\u2716 已达到本周期兑换上限"

    @ConfigKey("exchange-insufficient")
    var exchangeInsufficient: String = "{prefix}§c\u2716 余额不足，需要 {color}{symbol}{amount}"

    @Comment("排行榜")
    @ConfigKey("ranking-header")
    var rankingHeader: String = "\n§8 ┌─────────────────────────────┐\n§8 │  §e§l☆ §6§l{display_name} §e§l排行榜            §8│\n§8 ├─────────────────────────────┤"

    @ConfigKey("ranking-entry")
    var rankingEntry: String = "§8 │  §e#{rank} §f{player} §8- {color}{symbol}{balance}§8  │"

    @ConfigKey("ranking-footer")
    var rankingFooter: String = "§8 ├─────────────────────────────┤\n§8 │  §7第 §f{page}§7/{total_pages} 页 §8│ §7我的排名 §e#{my_rank}§8  │\n§8 └─────────────────────────────┘"

    @Comment("管理员")
    @ConfigKey("admin-give")
    var adminGive: String = "{prefix}§a\u2714 §f已向 §e{player} §f发放 {color}{symbol}{amount} §7({display_name})"

    @ConfigKey("admin-take")
    var adminTake: String = "{prefix}§a\u2714 §f已从 §e{player} §f扣除 {color}{symbol}{amount} §7({display_name})"

    @ConfigKey("admin-set")
    var adminSet: String = "{prefix}§a\u2714 §f已将 §e{player} §f的 {color}{display_name} §f设为 {color}{symbol}{amount}"

    @ConfigKey("admin-check-header")
    var adminCheckHeader: String = "\n§8 ┌─────────────────────────────┐\n§8 │  §6§l✦ §e{player} §7的钱包            §8│"

    @Comment("通用错误")
    @ConfigKey("error-currency-not-found")
    var errorCurrencyNotFound: String = "{prefix}§c\u2716 未找到货币 §f{id}"

    @ConfigKey("error-player-not-found")
    var errorPlayerNotFound: String = "{prefix}§c\u2716 未找到玩家 §f{name}"

    @ConfigKey("error-invalid-amount")
    var errorInvalidAmount: String = "{prefix}§c\u2716 请输入有效的金额"

    @ConfigKey("error-no-permission")
    var errorNoPermission: String = "{prefix}§c\u2716 权限不足"

    @ConfigKey("reload-success")
    var reloadSuccess: String = "{prefix}§a\u2714 §f配置已重载"

    @Comment("流水日志")
    @ConfigKey("log-header")
    var logHeader: String = "\n§8 ┌─────────────────────────────┐\n§8 │  §e§l☰ §6§l交易记录                  §8│\n§8 ├─────────────────────────────┤"

    @ConfigKey("log-entry")
    var logEntry: String = "§8 │  §7{time} {action}{color}{symbol}{amount} §8│ §7{source}§8  │"

    @ConfigKey("log-footer")
    var logFooter: String = "§8 └─────────────────────────────┘"

    @ConfigKey("log-empty")
    var logEmpty: String = "{prefix}§7暂无交易记录"

    companion object {
        lateinit var instance: MessageConfig private set
        fun load() { instance = MessageConfig(); instance.load() }
        fun reload() = instance.reload()
    }
}
