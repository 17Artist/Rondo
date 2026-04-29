package priv.seventeen.artist.rondo.exchange

import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import priv.seventeen.artist.blink.BlinkLog
import priv.seventeen.artist.rondo.Rondo
import priv.seventeen.artist.rondo.account.AccountManager
import priv.seventeen.artist.rondo.api.EconomyExchangeEvent
import priv.seventeen.artist.rondo.api.ExchangeResult
import priv.seventeen.artist.rondo.currency.CurrencyRegistry
import priv.seventeen.artist.rondo.currency.ExchangePeriod
import priv.seventeen.artist.rondo.currency.ExchangeRule
import priv.seventeen.artist.rondo.log.LogManager
import priv.seventeen.artist.rondo.log.TransactionLog
import priv.seventeen.artist.rondo.storage.StorageManager
import java.io.File
import java.math.BigDecimal
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 货币兑换管理器
 */
object ExchangeManager {

    private val rules = ConcurrentHashMap<String, ExchangeRule>()

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

        // 检查最小数量
        if (targetAmount < rule.minAmount) {
            return ExchangeResult(false, "最小兑换数量为 ${rule.minAmount}")
        }

        // 检查周期限购
        if (rule.maxPerPeriod > 0 && rule.period != ExchangePeriod.NONE) {
            val sinceTimestamp = getPeriodStart(rule.period)
            val used = StorageManager.provider.queryExchangeCount(player, ruleId, sinceTimestamp)
            if (used.add(targetAmount) > BigDecimal.valueOf(rule.maxPerPeriod.toLong())) {
                val remaining = BigDecimal.valueOf(rule.maxPerPeriod.toLong()).subtract(used)
                return ExchangeResult(false, "已达到本周期兑换上限，剩余额度: $remaining")
            }
        }

        // 计算消耗
        val fromAmount = rule.calculateCost(targetAmount)

        // 检查余额
        val balance = AccountManager.getBalance(player, rule.fromCurrency)
        if (balance < fromAmount) {
            return ExchangeResult(false, "余额不足，需要 ${fromCurrency.format(fromAmount)}", fromAmount = fromAmount)
        }

        // 触发事件
        val event = EconomyExchangeEvent(player, fromCurrency, toCurrency, fromAmount, targetAmount)
        Bukkit.getPluginManager().callEvent(event)
        if (event.isCancelled) return ExchangeResult(false, "兑换被取消")

        // 执行扣款
        val withdrawSuccess = AccountManager.withdrawOffline(player, rule.fromCurrency, fromAmount, "exchange:$ruleId")
        if (!withdrawSuccess) return ExchangeResult(false, "扣款失败")

        // 执行存入
        val depositSuccess = AccountManager.depositOffline(player, rule.toCurrency, targetAmount, "exchange:$ruleId")
        if (!depositSuccess) {
            // 回滚
            AccountManager.depositOffline(player, rule.fromCurrency, fromAmount, "exchange:rollback:$ruleId")
            return ExchangeResult(false, "存入失败")
        }

        // 记录兑换（限购计数）
        try {
            StorageManager.provider.insertExchangeRecord(player, ruleId, targetAmount)
        } catch (e: Exception) {
            BlinkLog.warn("Failed to insert exchange record for $player/$ruleId: ${e.message}")
        }

        // 记录日志
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
        val file = File(Rondo.plugin.dataFolder, "exchange.yml")
        if (!file.exists()) {
            Rondo.plugin.saveResource("exchange.yml", false)
        }
        if (!file.exists()) return

        val config: YamlConfiguration = YamlConfiguration.loadConfiguration(file)
        val section = config.getConfigurationSection("exchanges") ?: return

        for (key in section.getKeys(false)) {
            val ruleSection = section.getConfigurationSection(key) ?: continue
            try {
                val rule = ExchangeRule(
                    id = key,
                    fromCurrency = ruleSection.getString("from") ?: continue,
                    toCurrency = ruleSection.getString("to") ?: continue,
                    rate = BigDecimal(ruleSection.getString("rate") ?: "1"),
                    minAmount = BigDecimal(ruleSection.getString("min-amount") ?: "1"),
                    maxPerPeriod = ruleSection.getInt("max-per-period", -1),
                    period = try {
                        ExchangePeriod.valueOf(ruleSection.getString("period")?.uppercase() ?: "NONE")
                    } catch (_: Exception) { ExchangePeriod.NONE },
                    enabled = ruleSection.getBoolean("enabled", true)
                )
                rules[key] = rule
                BlinkLog.info("Loaded exchange rule: $key (${rule.fromCurrency} -> ${rule.toCurrency})")
            } catch (e: Exception) {
                BlinkLog.warn("Failed to load exchange rule '$key': ${e.message}")
            }
        }
        BlinkLog.info("Loaded ${rules.size} exchange rules.")
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
