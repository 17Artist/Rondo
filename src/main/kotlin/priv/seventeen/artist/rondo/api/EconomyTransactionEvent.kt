package priv.seventeen.artist.rondo.api

import org.bukkit.Bukkit
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import priv.seventeen.artist.rondo.currency.Currency
import java.math.BigDecimal
import java.util.UUID

/**
 * 货币变动事件 — 在操作执行前触发，可取消
 */
class EconomyTransactionEvent(
    val playerUuid: UUID,
    val currency: Currency,
    val action: Action,
    val amount: BigDecimal,
    val oldBalance: BigDecimal,
    val newBalance: BigDecimal,
    val source: String
) : Event(!Bukkit.isPrimaryThread()), Cancellable {

    enum class Action {
        DEPOSIT, WITHDRAW, SET
    }

    private var cancelled = false

    override fun isCancelled(): Boolean = cancelled
    override fun setCancelled(cancel: Boolean) { cancelled = cancel }

    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic
        val handlerList = HandlerList()
    }
}
