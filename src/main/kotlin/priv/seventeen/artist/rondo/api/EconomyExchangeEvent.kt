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
