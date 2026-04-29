package priv.seventeen.artist.rondo.currency

import org.bukkit.configuration.file.YamlConfiguration
import priv.seventeen.artist.blink.BlinkLog
import priv.seventeen.artist.rondo.Rondo
import java.io.File
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * 货币注册表 — 管理所有已注册货币
 */
object CurrencyRegistry {

    private val currencies = ConcurrentHashMap<String, Currency>()

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
        val folder = File(Rondo.plugin.dataFolder, "currencies")
        if (!folder.exists()) {
            folder.mkdirs()
            saveDefaults(folder)
        }
        val files = folder.listFiles { file: File -> file.extension == "yml" } ?: return
        for (file in files) {
            try {
                val currency = loadFromFile(file)
                currencies[currency.id.lowercase()] = currency
                BlinkLog.info("Loaded currency: ${currency.id} (${currency.displayName})")
            } catch (e: Exception) {
                BlinkLog.warn("Failed to load currency from ${file.name}: ${e.message}")
            }
        }
        BlinkLog.info("Loaded ${currencies.size} currencies.")
    }

    /** 重载 */
    fun reload() {
        loadAll()
    }

    private fun loadFromFile(file: File): Currency {
        val config: YamlConfiguration = YamlConfiguration.loadConfiguration(file)
        val id = config.getString("id") ?: file.nameWithoutExtension
        return Currency(
            id = id,
            displayName = config.getString("display-name") ?: id,
            symbol = config.getString("symbol") ?: "$",
            color = (config.getString("color") ?: "§f").let { translateColor(it) },
            description = config.getString("description") ?: "",
            decimalPlaces = config.getInt("decimal-places", 0),
            maxBalance = BigDecimal(config.getString("max-balance") ?: "-1"),
            defaultBalance = BigDecimal(config.getString("default-balance") ?: "0"),
            negativeAllowed = config.getBoolean("negative-allowed", false),
            tradeable = config.getBoolean("tradeable", false),
            transferable = config.getBoolean("transferable", false),
            transferTaxRate = config.getDouble("transfer-tax-rate", 0.0),
            vaultPrimary = config.getBoolean("vault-primary", false),
            rankingEnabled = config.getBoolean("ranking-enabled", true)
        )
    }

    private fun translateColor(color: String): String {
        // 支持 §代码 或颜色名称
        val colorMap = mapOf(
            "BLACK" to "§0", "DARK_BLUE" to "§1", "DARK_GREEN" to "§2",
            "DARK_AQUA" to "§3", "DARK_RED" to "§4", "DARK_PURPLE" to "§5",
            "GOLD" to "§6", "GRAY" to "§7", "DARK_GRAY" to "§8",
            "BLUE" to "§9", "GREEN" to "§a", "AQUA" to "§b",
            "RED" to "§c", "LIGHT_PURPLE" to "§d", "YELLOW" to "§e",
            "WHITE" to "§f"
        )
        return colorMap[color.uppercase()] ?: color
    }

    private fun saveDefaults(folder: File) {
        val defaults = listOf(
            "dark_coin.yml", "lithium.yml", "star_yuan.yml",
            "protocol_ticket.yml", "star_dust.yml", "star_glory.yml"
        )
        for (name in defaults) {
            val resource = Rondo.plugin.getResource("currencies/$name")
            if (resource != null) {
                val target = File(folder, name)
                target.writeBytes(resource.readBytes())
            }
        }
    }
}
