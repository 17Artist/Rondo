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

package priv.seventeen.artist.rondo.currency

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MoneyConstraintsTest {

    @Test
    fun `accepts decimal 20 4 boundaries`() {
        assertTrue(MoneyConstraints.isStorable(MoneyConstraints.MAX_ABSOLUTE_BALANCE))
        assertTrue(MoneyConstraints.isStorable(MoneyConstraints.MIN_ABSOLUTE_BALANCE))
        assertTrue(MoneyConstraints.isStorable(BigDecimal("1.23000")))
    }

    @Test
    fun `rejects overflow and excess meaningful scale`() {
        assertFalse(MoneyConstraints.isStorable(BigDecimal("10000000000000000")))
        assertFalse(MoneyConstraints.isStorable(BigDecimal("0.00001")))
    }

    @Test
    fun `enforces decimal 38 4 cumulative boundary`() {
        assertTrue(MoneyConstraints.isCumulativeStorable(MoneyConstraints.MAX_CUMULATIVE_AMOUNT))
        assertFalse(
            MoneyConstraints.isCumulativeStorable(
                MoneyConstraints.MAX_CUMULATIVE_AMOUNT.add(BigDecimal("0.0001"))
            )
        )
        assertFalse(MoneyConstraints.isCumulativeStorable(BigDecimal("-0.0001")))
    }
}
