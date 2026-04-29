package priv.seventeen.artist.rondo.log

import java.math.BigDecimal
import java.util.UUID

/**
 * 交易流水日志
 */
data class TransactionLog(
    val playerUuid: UUID,
    val currencyId: String,
    val action: Action,
    val amount: BigDecimal,
    val balanceAfter: BigDecimal,
    val source: String,
    val detail: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class Action {
        DEPOSIT,
        WITHDRAW,
        TRANSFER_IN,
        TRANSFER_OUT,
        SET,
        EXCHANGE_IN,
        EXCHANGE_OUT
    }
}
