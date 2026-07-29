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

package priv.seventeen.artist.rondo.currency

import priv.seventeen.artist.blink.BlinkLog
import priv.seventeen.artist.blink.bukkitPlugin
import priv.seventeen.artist.blink.config.BlinkConfig
import priv.seventeen.artist.blink.config.BlinkConfigFolder
import priv.seventeen.artist.blink.config.Comment
import priv.seventeen.artist.blink.config.ConfigKey
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.math.BigDecimal

/**
 * 单个货币的 BlinkConfig
 */
class CurrencyConfig(plugin: JavaPlugin, path: String) : BlinkConfig(plugin, path) {

    var id: String = ""

    @ConfigKey("display-name")
    var displayName: String = ""

    var symbol: String = "$"
    var color: String = "WHITE"
    var description: String = ""

    @ConfigKey("decimal-places")
    var decimalPlaces: Int = 0

    @ConfigKey("max-balance")
    var maxBalance: String = "-1"

    @ConfigKey("default-balance")
    var defaultBalance: String = "0"

    @ConfigKey("negative-allowed")
    var negativeAllowed: Boolean = false

    var tradeable: Boolean = false
    var transferable: Boolean = false

    @ConfigKey("transfer-tax-rate")
    var transferTaxRate: String = "0"

    @ConfigKey("vault-primary")
    var vaultPrimary: Boolean = false

    @ConfigKey("ranking-enabled")
    var rankingEnabled: Boolean = true

    /** 转换为不可变的 Currency 数据对象 */
    fun toCurrency(): Currency {
        val canonicalId = id.trim().lowercase()
        require(canonicalId.matches(Regex("[a-z0-9][a-z0-9_-]{0,63}"))) {
            "id 必须为 1-64 位小写字母、数字、下划线或连字符"
        }
        require(id == canonicalId) { "id 必须使用规范小写形式 '$canonicalId'" }
        require(decimalPlaces in 0..MoneyConstraints.MAX_DECIMAL_PLACES) {
            "decimal-places 必须在 0..${MoneyConstraints.MAX_DECIMAL_PLACES} 之间"
        }
        val parsedTransferTaxRate = BigDecimal(transferTaxRate)
        require(parsedTransferTaxRate in BigDecimal.ZERO..BigDecimal.ONE) {
            "transfer-tax-rate 必须在 0..1 之间"
        }
        require(parsedTransferTaxRate.stripTrailingZeros().scale().coerceAtLeast(0) <= 12) {
            "transfer-tax-rate 最多支持 12 位有效小数"
        }

        val parsedMaxBalance = BigDecimal(maxBalance)
        val parsedDefaultBalance = BigDecimal(defaultBalance)
        val unlimited = parsedMaxBalance.compareTo(BigDecimal.ONE.negate()) == 0
        require(unlimited || parsedMaxBalance >= BigDecimal.ZERO) {
            "max-balance 只能为 -1 或非负数"
        }
        if (!unlimited) {
            MoneyConstraints.requireStorable(parsedMaxBalance, "max-balance")
        }
        MoneyConstraints.requireStorable(parsedDefaultBalance, "default-balance")
        require(negativeAllowed || parsedDefaultBalance >= BigDecimal.ZERO) {
            "negative-allowed=false 时 default-balance 不能为负数"
        }
        require(unlimited || parsedDefaultBalance <= parsedMaxBalance) {
            "default-balance 不能超过 max-balance"
        }

        return Currency(
            id = canonicalId,
            displayName = displayName.ifBlank { canonicalId },
            symbol = symbol,
            color = translateColor(color),
            description = description,
            decimalPlaces = decimalPlaces,
            maxBalance = parsedMaxBalance,
            defaultBalance = parsedDefaultBalance,
            negativeAllowed = negativeAllowed,
            tradeable = tradeable,
            transferable = transferable,
            transferTaxRate = parsedTransferTaxRate,
            vaultPrimary = vaultPrimary,
            rankingEnabled = rankingEnabled
        )
    }

    companion object {
        private val colorMap = mapOf(
            "BLACK" to "§0", "DARK_BLUE" to "§1", "DARK_GREEN" to "§2",
            "DARK_AQUA" to "§3", "DARK_RED" to "§4", "DARK_PURPLE" to "§5",
            "GOLD" to "§6", "GRAY" to "§7", "DARK_GRAY" to "§8",
            "BLUE" to "§9", "GREEN" to "§a", "AQUA" to "§b",
            "RED" to "§c", "LIGHT_PURPLE" to "§d", "YELLOW" to "§e",
            "WHITE" to "§f"
        )

        fun translateColor(color: String): String = colorMap[color.uppercase()] ?: color
    }
}

/**
 * 货币配置文件夹 — 管理 currencies/ 下所有 yml
 */
class CurrencyConfigs : BlinkConfigFolder<CurrencyConfig>(bukkitPlugin, "currencies") {

    private val defaults = listOf(
        "gold.yml", "points.yml", "honor.yml"
    )

    override fun createConfig(plugin: JavaPlugin, filePath: String): CurrencyConfig {
        return CurrencyConfig(plugin, filePath)
    }

    override fun onCreateFolder(plugin: JavaPlugin, folderPath: String) {
        // 首次创建目录时释放默认货币配置
        val folder = File(plugin.dataFolder, folderPath)
        for (name in defaults) {
            val resource = plugin.getResource("assets/${folderPath}$name") ?: continue
            val target = File(folder, name)
            if (!target.exists()) {
                target.writeBytes(resource.readBytes())
            }
        }
    }
}

/**
 * 货币注册表 — 管理所有已注册货币
 */
object CurrencyRegistry {

    @Volatile
    private var currencies: Map<String, Currency> = emptyMap()
    private lateinit var configFolder: CurrencyConfigs

    /** 获取所有已注册货币 */
    fun getAll(): Collection<Currency> = currencies.values

    /** 根据 ID 获取货币 */
    fun get(id: String): Currency? = currencies[id.lowercase()]

    /** 是否已注册 */
    fun isRegistered(id: String): Boolean = currencies.containsKey(id.lowercase())

    /** 获取 Vault 主货币 */
    fun getVaultPrimary(): Currency? = currencies.values.firstOrNull { it.vaultPrimary }

    /** 获取所有货币 ID */
    fun getIds(): Set<String> = currencies.keys.toSet()

    /** 加载所有货币配置 */
    fun loadAll() {
        val candidateFolder = CurrencyConfigs()
        candidateFolder.load()
        val loaded = linkedMapOf<String, Currency>()

        for ((path, config) in candidateFolder.configs.toSortedMap()) {
            try {
                val currency = config.toCurrency()
                val fileId = File(path).nameWithoutExtension.lowercase()
                require(fileId.isBlank() || fileId == currency.id) {
                    "文件名 '$fileId' 必须与货币 id '${currency.id}' 一致"
                }
                require(currency.id !in loaded) { "货币 id '${currency.id}' 重复" }
                loaded[currency.id] = currency
                BlinkLog.info("已加载货币 §b${currency.id} §f(${currency.displayName})")
            } catch (e: Exception) {
                throw IllegalStateException("加载货币失败 $path: ${e.message}", e)
            }
        }
        require(loaded.isNotEmpty()) { "至少需要配置一种有效货币" }
        require(loaded.values.count { it.vaultPrimary } <= 1) {
            "只能配置一个 vault-primary=true 的货币"
        }

        configFolder = candidateFolder
        currencies = loaded.toMap()
        BlinkLog.info("已加载 §b${loaded.size} §f个货币")
    }

    /** 重载 */
    fun reload() {
        loadAll()
    }

    internal fun snapshot(): Map<String, Currency> = currencies

    internal fun restore(snapshot: Map<String, Currency>) {
        currencies = snapshot
    }
}
