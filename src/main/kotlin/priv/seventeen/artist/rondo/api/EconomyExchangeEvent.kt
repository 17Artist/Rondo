package priv.seventeen.artist.rondo.api

import org.bukkit.Bukkit
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import priv.seventeen.artist.rondo.currency.Currency
import java.math.BigDecimal
import java.util.UUID

/**
 * 兑换事件 — 在兑换执行前触发，可取消
 */
class EconomyExchangeEvent(
    val playerUuid: UUID,
    val fromCurrency: Currency,
    val toCurrency: Currency,
    val fromAmount: BigDecimal,
    val toAmount: BigDecimal
) : Event(!Bukkit.isPrimaryThread()), Cancellable {

    private var cancelled = false

    override fun isCancelled(): Boolean = cancelled
    override fun setCancelled(cancel: Boolean) { cancelled = cancel }

    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic
        val handlerList = HandlerList()
    }
}
