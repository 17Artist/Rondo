package priv.seventeen.artist.rondo.currency

import priv.seventeen.artist.blink.BlinkLog
import priv.seventeen.artist.blink.bukkitPlugin
import priv.seventeen.artist.blink.config.BlinkConfig
import priv.seventeen.artist.blink.config.BlinkConfigFolder
import priv.seventeen.artist.blink.config.Comment
import priv.seventeen.artist.blink.config.ConfigKey
import org.bukkit.plugin.java.JavaPlugin
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

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
    var transferTaxRate: Double = 0.0

    @ConfigKey("vault-primary")
    var vaultPrimary: Boolean = false

    @ConfigKey("ranking-enabled")
    var rankingEnabled: Boolean = true

    /** 转换为不可变的 Currency 数据对象 */
    fun toCurrency(): Currency {
        return Currency(
            id = id,
            displayName = displayName.ifEmpty { id },
            symbol = symbol,
            color = translateColor(color),
            description = description,
            decimalPlaces = decimalPlaces,
            maxBalance = BigDecimal(maxBalance),
            defaultBalance = BigDecimal(defaultBalance),
            negativeAllowed = negativeAllowed,
            tradeable = tradeable,
            transferable = transferable,
            transferTaxRate = transferTaxRate,
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
        val folder = java.io.File(plugin.dataFolder, folderPath)
        for (name in defaults) {
            val resource = plugin.getResource("assets/${folderPath}$name") ?: continue
            val target = java.io.File(folder, name)
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

    private val currencies = ConcurrentHashMap<String, Currency>()
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
        currencies.clear()
        configFolder = CurrencyConfigs()
        configFolder.load()

        for ((path, config) in configFolder.configs) {
            try {
                val currency = config.toCurrency()
                currencies[currency.id.lowercase()] = currency
                BlinkLog.info("已加载货币 §b${currency.id} §f(${currency.displayName})")
            } catch (e: Exception) {
                BlinkLog.warn("加载货币失败 $path: ${e.message}")
            }
        }
        BlinkLog.info("已加载 §b${currencies.size} §f个货币")
    }

    /** 重载 */
    fun reload() {
        loadAll()
    }
}
