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

import org.junit.jupiter.api.Test
import priv.seventeen.artist.rondo.api.PlayerEconomySnapshot
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class EconomyReadCacheTest {

    @Test
    fun `online snapshot does not expire and offline snapshot does`() {
        var now = 1_000L
        val cache = EconomyReadCache { now }
        val playerUuid = UUID.randomUUID()
        val snapshot = PlayerEconomySnapshot(
            playerUuid = playerUuid,
            currencies = emptyMap(),
            revision = 1,
            updatedAtEpochMillis = now
        )

        cache.publishOnline(snapshot)
        now = Long.MAX_VALUE - 1
        assertSame(snapshot, cache.get(playerUuid))

        now = 2_000L
        cache.markOffline(playerUuid, 500L)
        assertSame(snapshot, cache.get(playerUuid))
        now = 2_500L
        assertNull(cache.get(playerUuid))
    }

    @Test
    fun `cleanup removes only expired offline entries`() {
        var now = 10L
        val cache = EconomyReadCache { now }
        val online = snapshot()
        val offline = snapshot()

        cache.publishOnline(online)
        cache.publishOffline(offline, 10L)
        now = 20L

        assertEquals(1, cache.removeExpired())
        assertSame(online, cache.get(online.playerUuid))
        assertNull(cache.get(offline.playerUuid))
        assertEquals(1, cache.size())
    }

    @Test
    fun `offline capacity evicts oldest expiry without evicting online entries`() {
        var now = 100L
        val cache = EconomyReadCache(maxOfflineEntries = 1) { now }
        val online = snapshot()
        val firstOffline = snapshot()
        val secondOffline = snapshot()

        cache.publishOnline(online)
        cache.publishOffline(firstOffline, 1_000L)
        now++
        cache.publishOffline(secondOffline, 1_000L)

        assertSame(online, cache.get(online.playerUuid))
        assertNull(cache.get(firstOffline.playerUuid))
        assertSame(secondOffline, cache.get(secondOffline.playerUuid))
        assertEquals(2, cache.size())
    }

    private fun snapshot() = PlayerEconomySnapshot(
        playerUuid = UUID.randomUUID(),
        currencies = emptyMap(),
        revision = 0,
        updatedAtEpochMillis = 0
    )
}
