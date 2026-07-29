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

package priv.seventeen.artist.rondo.exchange

import org.junit.jupiter.api.Test
import priv.seventeen.artist.rondo.currency.ExchangePeriod
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals

class ExchangePeriodWindowTest {

    @Test
    fun `daily period starts at midnight in server timezone`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = ZonedDateTime.of(2026, 7, 29, 10, 30, 0, 0, zone)

        assertEquals(
            Instant.parse("2026-07-28T16:00:00Z").toEpochMilli(),
            ExchangePeriodWindow.startMillis(ExchangePeriod.DAILY, now)
        )
    }

    @Test
    fun `weekly period starts on local Monday`() {
        val zone = ZoneId.of("America/Toronto")
        val now = ZonedDateTime.of(2026, 7, 29, 15, 0, 0, 0, zone)

        assertEquals(
            ZonedDateTime.of(2026, 7, 27, 0, 0, 0, 0, zone).toInstant().toEpochMilli(),
            ExchangePeriodWindow.startMillis(ExchangePeriod.WEEKLY, now)
        )
    }

    @Test
    fun `monthly boundary respects daylight saving offset`() {
        val zone = ZoneId.of("America/Toronto")
        val now = ZonedDateTime.of(2026, 11, 20, 12, 0, 0, 0, zone)

        assertEquals(
            ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli(),
            ExchangePeriodWindow.startMillis(ExchangePeriod.MONTHLY, now)
        )
    }

    @Test
    fun `none period has stable zero key`() {
        val now = ZonedDateTime.of(
            2026, 7, 29, 15, 0, 0, 0,
            ZoneId.of("Pacific/Auckland")
        )

        assertEquals(0L, ExchangePeriodWindow.startMillis(ExchangePeriod.NONE, now))
    }
}
