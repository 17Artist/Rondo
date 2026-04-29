package priv.seventeen.artist.rondo.vault

import net.milkbowl.vault.economy.AbstractEconomy
import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.ServicePriority
import priv.seventeen.artist.blink.BlinkLog
import priv.seventeen.artist.blink.bukkitPlugin
import priv.seventeen.artist.rondo.api.RondoAPI
import priv.seventeen.artist.rondo.currency.Currency
import priv.seventeen.artist.rondo.currency.CurrencyRegistry
import java.math.BigDecimal

/**
 * Vault Economy 对接
 */
object VaultHook {

    private var registered = false
    private var economyInstance: RondoEconomy? = null

    fun hook() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            BlinkLog.info("Vault not found, skipping hook.")
            return
        }

        val primary = CurrencyRegistry.getVaultPrimary()
        if (primary == null) {
            BlinkLog.info("No vault-primary currency configured, skipping Vault hook.")
            return
        }

        val economy = RondoEconomy(primary)
        economyInstance = economy
        Bukkit.getServicesManager().register(
            Economy::class.java,
            economy,
            bukkitPlugin,
            ServicePriority.Highest
        )
        registered = true
        BlinkLog.info("Vault hooked with primary currency: ${primary.id}")
    }

    fun unhook() {
        if (registered) {
            economyInstance?.let {
                Bukkit.getServicesManager().unregister(Economy::class.java, it)
            }
            economyInstance = null
            registered = false
        }
    }
}

/**
 * Vault Economy 实现
 */
class RondoEconomy(private val currency: Currency) : AbstractEconomy() {

    override fun isEnabled(): Boolean = true
    override fun getName(): String = "Rondo"
    override fun hasBankSupport(): Boolean = false
    override fun fractionalDigits(): Int = currency.decimalPlaces
    override fun currencyNamePlural(): String = currency.displayName
    override fun currencyNameSingular(): String = currency.displayName

    override fun format(amount: Double): String {
        return currency.format(BigDecimal.valueOf(amount))
    }

    override fun hasAccount(player: OfflinePlayer): Boolean = true
    override fun hasAccount(playerName: String): Boolean = true
    override fun hasAccount(player: OfflinePlayer, worldName: String): Boolean = true
    override fun hasAccount(playerName: String, worldName: String): Boolean = true

    override fun createPlayerAccount(player: OfflinePlayer): Boolean = true
    override fun createPlayerAccount(playerName: String): Boolean = true
    override fun createPlayerAccount(player: OfflinePlayer, worldName: String): Boolean = true
    override fun createPlayerAccount(playerName: String, worldName: String): Boolean = true

    override fun getBalance(player: OfflinePlayer): Double {
        return RondoAPI.getBalance(player.uniqueId, currency.id).toDouble()
    }

    @Suppress("DEPRECATION")
    override fun getBalance(playerName: String): Double {
        val player = Bukkit.getOfflinePlayer(playerName)
        return getBalance(player)
    }

    override fun getBalance(player: OfflinePlayer, world: String): Double = getBalance(player)
    override fun getBalance(playerName: String, world: String): Double = getBalance(playerName)

    override fun has(player: OfflinePlayer, amount: Double): Boolean {
        return RondoAPI.hasBalance(player.uniqueId, currency.id, BigDecimal.valueOf(amount))
    }

    @Suppress("DEPRECATION")
    override fun has(playerName: String, amount: Double): Boolean {
        val player = Bukkit.getOfflinePlayer(playerName)
        return has(player, amount)
    }

    override fun has(player: OfflinePlayer, world: String, amount: Double): Boolean = has(player, amount)
    override fun has(playerName: String, world: String, amount: Double): Boolean = has(playerName, amount)

    override fun withdrawPlayer(player: OfflinePlayer, amount: Double): EconomyResponse {
        if (amount < 0) return EconomyResponse(0.0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "Cannot withdraw negative amount")
        val success = RondoAPI.withdraw(player.uniqueId, currency.id, BigDecimal.valueOf(amount), "vault")
        val balance = getBalance(player)
        return if (success) {
            EconomyResponse(amount, balance, EconomyResponse.ResponseType.SUCCESS, null)
        } else {
            EconomyResponse(0.0, balance, EconomyResponse.ResponseType.FAILURE, "Insufficient funds")
        }
    }

    @Suppress("DEPRECATION")
    override fun withdrawPlayer(playerName: String, amount: Double): EconomyResponse {
        val player = Bukkit.getOfflinePlayer(playerName)
        return withdrawPlayer(player, amount)
    }

    override fun withdrawPlayer(player: OfflinePlayer, world: String, amount: Double): EconomyResponse = withdrawPlayer(player, amount)
    override fun withdrawPlayer(playerName: String, world: String, amount: Double): EconomyResponse = withdrawPlayer(playerName, amount)

    override fun depositPlayer(player: OfflinePlayer, amount: Double): EconomyResponse {
        if (amount < 0) return EconomyResponse(0.0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "Cannot deposit negative amount")
        val success = RondoAPI.deposit(player.uniqueId, currency.id, BigDecimal.valueOf(amount), "vault")
        val balance = getBalance(player)
        return if (success) {
            EconomyResponse(amount, balance, EconomyResponse.ResponseType.SUCCESS, null)
        } else {
            EconomyResponse(0.0, balance, EconomyResponse.ResponseType.FAILURE, "Deposit failed")
        }
    }

    @Suppress("DEPRECATION")
    override fun depositPlayer(playerName: String, amount: Double): EconomyResponse {
        val player = Bukkit.getOfflinePlayer(playerName)
        return depositPlayer(player, amount)
    }

    override fun depositPlayer(player: OfflinePlayer, world: String, amount: Double): EconomyResponse = depositPlayer(player, amount)
    override fun depositPlayer(playerName: String, world: String, amount: Double): EconomyResponse = depositPlayer(playerName, amount)

    // Bank methods - not supported
    override fun createBank(name: String, player: OfflinePlayer): EconomyResponse = EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks not supported")
    override fun createBank(name: String, player: String): EconomyResponse = EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks not supported")
    override fun deleteBank(name: String): EconomyResponse = EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks not supported")
    override fun bankBalance(name: String): EconomyResponse = EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks not supported")
    override fun bankHas(name: String, amount: Double): EconomyResponse = EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks not supported")
    override fun bankWithdraw(name: String, amount: Double): EconomyResponse = EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks not supported")
    override fun bankDeposit(name: String, amount: Double): EconomyResponse = EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks not supported")
    override fun isBankOwner(name: String, player: OfflinePlayer): EconomyResponse = EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks not supported")
    override fun isBankOwner(name: String, playerName: String): EconomyResponse = EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks not supported")
    override fun isBankMember(name: String, player: OfflinePlayer): EconomyResponse = EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks not supported")
    override fun isBankMember(name: String, playerName: String): EconomyResponse = EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks not supported")
    override fun getBanks(): MutableList<String> = mutableListOf()
}
