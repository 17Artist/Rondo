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

package priv.seventeen.artist.rondo.account

import priv.seventeen.artist.rondo.api.PlayerEconomySnapshot
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 在线快照永不过期；玩家离线后保留一段时间，为 PAPI/Vault 的离线展示提供热数据。
 */
internal class EconomyReadCache(
    maxOfflineEntries: Int = 10_000,
    private val clockMillis: () -> Long = System::currentTimeMillis
) {
    private data class Entry(
        val snapshot: PlayerEconomySnapshot,
        val expiresAtMillis: Long,
        val online: Boolean
    )

    private val entries = ConcurrentHashMap<UUID, Entry>()
    private val maintenanceLock = Any()
    private var offlineEntryCount = 0

    @Volatile
    private var maxOfflineEntries = maxOfflineEntries.coerceAtLeast(1)

    fun configure(maxOfflineEntries: Int) {
        synchronized(maintenanceLock) {
            this.maxOfflineEntries = maxOfflineEntries.coerceAtLeast(1)
            enforceOfflineLimit()
        }
    }

    fun get(playerUuid: UUID): PlayerEconomySnapshot? {
        val entry = entries[playerUuid] ?: return null
        if (!entry.online && entry.expiresAtMillis <= clockMillis()) {
            synchronized(maintenanceLock) {
                if (entries.remove(playerUuid, entry)) offlineEntryCount--
                return entries[playerUuid]?.snapshot
            }
        }
        return entry.snapshot
    }

    fun publishOnline(snapshot: PlayerEconomySnapshot) {
        synchronized(maintenanceLock) {
            val previous = entries.put(
                snapshot.playerUuid,
                Entry(snapshot, Long.MAX_VALUE, online = true)
            )
            if (previous != null && !previous.online) offlineEntryCount--
        }
    }

    fun publishOffline(snapshot: PlayerEconomySnapshot, ttlMillis: Long) {
        synchronized(maintenanceLock) {
            if (ttlMillis <= 0L) {
                val previous = entries.remove(snapshot.playerUuid)
                if (previous != null && !previous.online) offlineEntryCount--
                return
            }
            val previous = entries.put(
                snapshot.playerUuid,
                Entry(
                    snapshot,
                    safeExpiry(clockMillis(), ttlMillis),
                    online = false
                )
            )
            if (previous == null || previous.online) offlineEntryCount++
            enforceOfflineLimit()
        }
    }

    fun markOffline(playerUuid: UUID, ttlMillis: Long) {
        synchronized(maintenanceLock) {
            if (ttlMillis <= 0L) {
                val previous = entries.remove(playerUuid)
                if (previous != null && !previous.online) offlineEntryCount--
                return
            }
            entries.computeIfPresent(playerUuid) { _, entry ->
                if (entry.online) offlineEntryCount++
                entry.copy(
                    expiresAtMillis = safeExpiry(clockMillis(), ttlMillis),
                    online = false
                )
            }
            enforceOfflineLimit()
        }
    }

    fun removeExpired(): Int {
        synchronized(maintenanceLock) {
            val now = clockMillis()
            var removed = 0
            for ((playerUuid, entry) in entries) {
                if (!entry.online &&
                    entry.expiresAtMillis <= now &&
                    entries.remove(playerUuid, entry)
                ) {
                    offlineEntryCount--
                    removed++
                }
            }
            return removed
        }
    }

    fun clear() {
        synchronized(maintenanceLock) {
            entries.clear()
            offlineEntryCount = 0
        }
    }

    internal fun size(): Int = entries.size

    private fun safeExpiry(now: Long, ttlMillis: Long): Long {
        return if (Long.MAX_VALUE - now < ttlMillis) Long.MAX_VALUE else now + ttlMillis
    }

    private fun enforceOfflineLimit() {
        val overflow = offlineEntryCount - maxOfflineEntries
        if (overflow <= 0) return

        entries.entries.asSequence()
            .filter { !it.value.online }
            .sortedBy { it.value.expiresAtMillis }
            .take(overflow)
            .forEach { (playerUuid, entry) ->
                if (entries.remove(playerUuid, entry)) offlineEntryCount--
            }
    }
}
