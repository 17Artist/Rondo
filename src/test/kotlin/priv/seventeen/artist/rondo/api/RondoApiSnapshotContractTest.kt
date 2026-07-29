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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture

class RondoApiSnapshotContractTest {

    @Test
    fun `exposes separate cache peek and loading snapshot contracts`() {
        val peek = RondoAPI::class.java.getMethod(
            "peekEconomySnapshot",
            UUID::class.java
        )
        val load = RondoAPI::class.java.getMethod(
            "getEconomySnapshot",
            UUID::class.java
        )

        assertEquals(PlayerEconomySnapshot::class.java, peek.returnType)
        assertEquals(CompletableFuture::class.java, load.returnType)
        assertThrows(NoSuchMethodException::class.java) {
            RondoAPI::class.java.getMethod(
                "getEconomySnapshotAsync",
                UUID::class.java
            )
        }
    }
}
