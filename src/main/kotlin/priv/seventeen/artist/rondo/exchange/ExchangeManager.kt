package priv.seventeen.artist.rondo.exchange

import org.bukkit.Bukkit
import priv.seventeen.artist.blink.BlinkLog
import priv.seventeen.artist.blink.bukkitPlugin
import priv.seventeen.artist.blink.config.BlinkConfig
import priv.seventeen.artist.blink.config.BlinkSection
import priv.seventeen.artist.blink.config.ConfigKey
import priv.seventeen.artist.rondo.account.AccountManager
import priv.seventeen.artist.rondo.api.EconomyExchangeEvent
import priv.seventeen.artist.rondo.api.ExchangeResult
import priv.seventeen.artist.rondo.currency.CurrencyRegistry
import priv.seventeen.artist.rondo.currency.ExchangePeriod
import priv.seventeen.artist.rondo.currency.ExchangeRule
import priv.seventeen.artist.rondo.log.LogManager
import priv.seventeen.artist.rondo.log.TransactionLog
import priv.seventeen.artist.rondo.storage.StorageManager
import java.math.BigDecimal
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 兑换配置文件 (exchange.yml)
 */
class ExchangeConfig : BlinkConfig(bukkitPlugin, "exchange") {

    var exchanges: MutableMap<String, ExchangeRuleSection> = mutableMapOf()
}

/**
 * 单条兑换规则的配置节
 */
class ExchangeRuleSection : BlinkSection() {
    var from: String = ""
    var to: String = ""
    var rate: String = "1"

    @ConfigKey("min-amount")
    var minAmount: String = "1"

    @ConfigKey("max-per-period")
    var maxPerPeriod: Int = -1

    var period: String = "NONE"
    var enabled: Boolean = true

    fun toRule(id: String): ExchangeRule {
        return ExchangeRule(
            id = id,
            fromCurrency = from,
            toCurrency = to,
            rate = BigDecimal(rate),
            minAmount = BigDecimal(minAmount),
            maxPerPeriod = maxPerPeriod,
            period = try { ExchangePeriod.valueOf(period.uppercase()) } catch (_: Exception) { ExchangePeriod.NONE },
            enabled = enabled
        )
    }
}

/**
 * 货币兑换管理器
 */
object ExchangeManager {

    private val rules = ConcurrentHashMap<String, ExchangeRule>()
    private lateinit var config: ExchangeConfig

    fun initialize() {
        loadRules()
    }

    fun reload() {
        loadRules()
    }

    fun getRule(id: String): ExchangeRule? = rules[id]

    fun getAllRules(): Collection<ExchangeRule> = rules.values

    /** 根据源和目标货币查找规则 */
    fun findRule(fromCurrency: String, toCurrency: String): ExchangeRule? {
        return rules.values.firstOrNull { it.fromCurrency == fromCurrency && it.toCurrency == toCurrency && it.enabled }
    }

    fun getRuleIds(): Set<String> = rules.keys.toSet()

    /** 执行兑换 */
    fun execute(player: UUID, ruleId: String, targetAmount: BigDecimal): ExchangeResult {
        val rule = rules[ruleId] ?: return ExchangeResult(false, "兑换规则不存在")
        if (!rule.enabled) return ExchangeResult(false, "该兑换规则已禁用")

        val fromCurrency = CurrencyRegistry.get(rule.fromCurrency) ?: return ExchangeResult(false, "源货币不存在")
        val toCurrency = CurrencyRegistry.get(rule.toCurrency) ?: return ExchangeResult(false, "目标货币不存在")

        if (targetAmount < rule.minAmount) {
            return ExchangeResult(false, "最小兑换数量为 ${rule.minAmount}")
        }

        if (rule.maxPerPeriod > 0 && rule.period != ExchangePeriod.NONE) {
            val sinceTimestamp = getPeriodStart(rule.period)
            val used = StorageManager.provider.queryExchangeCount(player, ruleId, sinceTimestamp)
            if (used.add(targetAmount) > BigDecimal.valueOf(rule.maxPerPeriod.toLong())) {
                val remaining = BigDecimal.valueOf(rule.maxPerPeriod.toLong()).subtract(used)
                return ExchangeResult(false, "已达到本周期兑换上限，剩余额度: $remaining")
            }
        }

        val fromAmount = rule.calculateCost(targetAmount)

        val balance = AccountManager.getBalance(player, rule.fromCurrency)
        if (balance < fromAmount) {
            return ExchangeResult(false, "余额不足，需要 ${fromCurrency.format(fromAmount)}", fromAmount = fromAmount)
        }

        val event = EconomyExchangeEvent(player, fromCurrency, toCurrency, fromAmount, targetAmount)
        Bukkit.getPluginManager().callEvent(event)
        if (event.isCancelled) return ExchangeResult(false, "兑换被取消")

        val withdrawSuccess = AccountManager.withdrawOffline(player, rule.fromCurrency, fromAmount, "exchange:$ruleId")
        if (!withdrawSuccess) return ExchangeResult(false, "扣款失败")

        val depositSuccess = AccountManager.depositOffline(player, rule.toCurrency, targetAmount, "exchange:$ruleId")
        if (!depositSuccess) {
            AccountManager.depositOffline(player, rule.fromCurrency, fromAmount, "exchange:rollback:$ruleId")
            return ExchangeResult(false, "存入失败")
        }

        try {
            StorageManager.provider.insertExchangeRecord(player, ruleId, targetAmount)
        } catch (e: Exception) {
            BlinkLog.warn("Failed to insert exchange record for $player/$ruleId: ${e.message}")
        }

        val fromBalance = AccountManager.getBalance(player, rule.fromCurrency)
        val toBalance = AccountManager.getBalance(player, rule.toCurrency)

        LogManager.submit(TransactionLog(
            playerUuid = player, currencyId = rule.fromCurrency,
            action = TransactionLog.Action.EXCHANGE_OUT,
            amount = fromAmount, balanceAfter = fromBalance,
            source = "exchange:$ruleId"
        ))
        LogManager.submit(TransactionLog(
            playerUuid = player, currencyId = rule.toCurrency,
            action = TransactionLog.Action.EXCHANGE_IN,
            amount = targetAmount, balanceAfter = toBalance,
            source = "exchange:$ruleId"
        ))

        return ExchangeResult(true, "兑换成功", fromAmount = fromAmount, toAmount = targetAmount)
    }

    private fun loadRules() {
        rules.clear()
        config = ExchangeConfig()
        config.load()

        for ((key, section) in config.exchanges) {
            try {
                val rule = section.toRule(key)
                rules[key] = rule
                BlinkLog.info("已加载兑换规则 §b$key §f(${rule.fromCurrency} -> ${rule.toCurrency})")
            } catch (e: Exception) {
                BlinkLog.warn("加载兑换规则失败 '$key': ${e.message}")
            }
        }
        BlinkLog.info("已加载 §b${rules.size} §f条兑换规则")
    }

    private fun getPeriodStart(period: ExchangePeriod): Long {
        val cal = Calendar.getInstance()
        when (period) {
            ExchangePeriod.DAILY -> {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
            }
            ExchangePeriod.WEEKLY -> {
                cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
            }
            ExchangePeriod.MONTHLY -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
            }
            ExchangePeriod.NONE -> return 0L
        }
        return cal.timeInMillis
    }
}
