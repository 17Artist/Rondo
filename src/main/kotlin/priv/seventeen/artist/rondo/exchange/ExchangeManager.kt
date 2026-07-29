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
import priv.seventeen.artist.rondo.currency.MoneyConstraints
import priv.seventeen.artist.rondo.log.LogManager
import priv.seventeen.artist.rondo.log.TransactionLog
import priv.seventeen.artist.rondo.storage.AtomicBalanceFailure
import priv.seventeen.artist.rondo.storage.ExchangeBalanceRequest
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneId
import java.util.UUID

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
        val canonicalId = id.trim().lowercase()
        require(canonicalId.matches(Regex("[a-z0-9][a-z0-9_-]{0,63}"))) {
            "规则 ID 必须为 1-64 位小写字母、数字、下划线或连字符"
        }
        require(id == canonicalId) { "规则 ID 必须使用规范小写形式 '$canonicalId'" }
        val parsedRate = BigDecimal(rate)
        val parsedMinAmount = BigDecimal(minAmount)
        require(parsedRate > BigDecimal.ZERO) { "rate 必须大于 0" }
        require(parsedMinAmount > BigDecimal.ZERO) { "min-amount 必须大于 0" }
        require(maxPerPeriod == -1 || maxPerPeriod > 0) {
            "max-per-period 只能为 -1 或正整数"
        }
        return ExchangeRule(
            id = canonicalId,
            fromCurrency = from.trim().lowercase(),
            toCurrency = to.trim().lowercase(),
            rate = parsedRate,
            minAmount = parsedMinAmount,
            maxPerPeriod = maxPerPeriod,
            period = ExchangePeriod.valueOf(period.uppercase()),
            enabled = enabled
        )
    }
}

/**
 * 货币兑换管理器
 */
object ExchangeManager {

    @Volatile
    private var rules: Map<String, ExchangeRule> = emptyMap()
    private lateinit var config: ExchangeConfig

    fun initialize() {
        loadRules()
    }

    fun reload() {
        loadRules()
    }

    internal fun snapshot(): Map<String, ExchangeRule> = rules

    internal fun restore(snapshot: Map<String, ExchangeRule>) {
        rules = snapshot
    }

    fun getRule(id: String): ExchangeRule? = rules[id.lowercase()]

    fun getAllRules(): Collection<ExchangeRule> = rules.values

    /** 根据源和目标货币查找规则（货币 ID 大小写不敏感，与货币注册表一致） */
    fun findRule(fromCurrency: String, toCurrency: String): ExchangeRule? {
        return rules.values.firstOrNull {
            it.fromCurrency.equals(fromCurrency, ignoreCase = true) &&
                it.toCurrency.equals(toCurrency, ignoreCase = true) &&
                it.enabled
        }
    }

    fun getRuleIds(): Set<String> = rules.keys.toSet()

    /** 执行兑换 */
    fun execute(player: UUID, ruleId: String, targetAmount: BigDecimal): ExchangeResult {
        val rule = rules[ruleId.lowercase()] ?: return ExchangeResult(false, "兑换规则不存在")
        if (!rule.enabled) return ExchangeResult(false, "该兑换规则已禁用")

        val fromCurrency = CurrencyRegistry.get(rule.fromCurrency) ?: return ExchangeResult(false, "源货币不存在")
        val toCurrency = CurrencyRegistry.get(rule.toCurrency) ?: return ExchangeResult(false, "目标货币不存在")

        // 归一到各自货币精度
        val target = targetAmount.setScale(toCurrency.decimalPlaces, RoundingMode.HALF_UP)
        if (target <= BigDecimal.ZERO) return ExchangeResult(false, "无效金额")

        if (target < rule.minAmount) {
            return ExchangeResult(false, "最小兑换数量为 ${rule.minAmount}")
        }

        val fromAmount = rule.calculateCost(target).setScale(fromCurrency.decimalPlaces, RoundingMode.HALF_UP)
        if (fromAmount <= BigDecimal.ZERO ||
            !MoneyConstraints.isStorable(fromAmount) ||
            !MoneyConstraints.isStorable(target)
        ) {
            return ExchangeResult(false, "兑换金额超出有效范围")
        }

        return AccountManager.withPlayerLock(player) {
            val balance = AccountManager.getBalance(player, rule.fromCurrency)
            if (balance < fromAmount) {
                return@withPlayerLock ExchangeResult(
                    false,
                    "余额不足，需要 ${fromCurrency.format(fromAmount)}",
                    fromAmount = fromAmount
                )
            }

            val event = EconomyExchangeEvent(player, fromCurrency, toCurrency, fromAmount, target)
            Bukkit.getPluginManager().callEvent(event)
            if (event.isCancelled) {
                return@withPlayerLock ExchangeResult(false, "兑换被取消")
            }

            val limited = rule.maxPerPeriod > 0 && rule.period != ExchangePeriod.NONE
            val atomic = AccountManager.exchange(
                ExchangeBalanceRequest(
                    playerUuid = player,
                    ruleId = rule.id,
                    fromCurrencyId = fromCurrency.id,
                    toCurrencyId = toCurrency.id,
                    debitAmount = fromAmount,
                    creditAmount = target,
                    allowNegative = fromCurrency.negativeAllowed,
                    fromInitialBalance = fromCurrency.defaultBalance,
                    toInitialBalance = toCurrency.defaultBalance,
                    toMaxBalance = toCurrency.maxBalance.takeIf { toCurrency.hasMaxBalance }
                        ?: MoneyConstraints.MAX_ABSOLUTE_BALANCE,
                    periodStart = ExchangePeriodWindow.startMillis(rule.period).takeIf { limited },
                    periodLimit = BigDecimal.valueOf(rule.maxPerPeriod.toLong()).takeIf { limited }
                )
            )
            if (!atomic.success) {
                val message = when (atomic.failure) {
                    AtomicBalanceFailure.INSUFFICIENT_FUNDS -> "余额不足"
                    AtomicBalanceFailure.BALANCE_LIMIT -> "目标货币余额将超过上限"
                    AtomicBalanceFailure.PERIOD_LIMIT -> "已达到本周期兑换上限"
                    else -> "兑换失败"
                }
                return@withPlayerLock ExchangeResult(false, message, fromAmount = fromAmount)
            }

            LogManager.submit(TransactionLog(
                playerUuid = player,
                currencyId = fromCurrency.id,
                action = TransactionLog.Action.EXCHANGE_OUT,
                amount = fromAmount,
                balanceAfter = AccountManager.getBalance(player, fromCurrency.id),
                source = "exchange:${rule.id}"
            ))
            LogManager.submit(TransactionLog(
                playerUuid = player,
                currencyId = toCurrency.id,
                action = TransactionLog.Action.EXCHANGE_IN,
                amount = target,
                balanceAfter = AccountManager.getBalance(player, toCurrency.id),
                source = "exchange:${rule.id}"
            ))

            ExchangeResult(true, "兑换成功", fromAmount = fromAmount, toAmount = target)
        }
    }

    private fun loadRules() {
        val candidateConfig = ExchangeConfig()
        candidateConfig.load()
        val loaded = linkedMapOf<String, ExchangeRule>()

        for ((key, section) in candidateConfig.exchanges.toSortedMap()) {
            try {
                val rule = section.toRule(key)
                require(rule.fromCurrency != rule.toCurrency) { "源货币和目标货币不能相同" }
                require(CurrencyRegistry.isRegistered(rule.fromCurrency)) {
                    "源货币 '${rule.fromCurrency}' 不存在"
                }
                require(CurrencyRegistry.isRegistered(rule.toCurrency)) {
                    "目标货币 '${rule.toCurrency}' 不存在"
                }
                require(rule.id !in loaded) { "兑换规则 '${rule.id}' 重复" }
                loaded[rule.id] = rule
            } catch (e: Exception) {
                throw IllegalStateException("加载兑换规则失败 '$key': ${e.message}", e)
            }
        }
        config = candidateConfig
        rules = loaded.toMap()
        BlinkLog.info("已加载 §b${loaded.size} §f条兑换规则")
        if (loaded.values.any { it.maxPerPeriod > 0 && it.period != ExchangePeriod.NONE }) {
            BlinkLog.info(
                "周期额度时区: §b${ZoneId.systemDefault().id}"
            )
        }
    }

}
