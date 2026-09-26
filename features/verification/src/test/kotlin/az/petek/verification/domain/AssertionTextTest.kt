/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package az.petek.verification.domain

import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.OracleCondition
import az.petek.campaign.domain.RequestPattern
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class AssertionTextTest {
    @Test
    fun `utf8Prefix keeps whole characters within the byte budget`() {
        AssertionText.utf8Prefix("abc", 10) shouldBe "abc"
        AssertionText.utf8Prefix("abcdef", 3) shouldBe "abc"
        // "ə" is 2 bytes: 3 bytes fit one of them, never half a character.
        AssertionText.utf8Prefix("əəə", 3) shouldBe "ə"
        // A 4-byte emoji (a surrogate pair) is kept or dropped as a whole.
        AssertionText.utf8Prefix("a😀b", 4) shouldBe "a"
        AssertionText.utf8Prefix("a😀b", 5) shouldBe "a😀"
        AssertionText.utf8Prefix("", 5) shouldBe ""
        AssertionText.utf8Prefix("abc", 0) shouldBe ""
    }

    @Test
    fun `clip marks what it cut`() {
        AssertionText.clip("short", 10) shouldBe "short"
        AssertionText.clip("0123456789", 4) shouldBe "0123… (6 more chars)"
    }

    @Test
    fun `error descriptions are single-line and bounded`() {
        AssertionText.error(IllegalStateException("first line\nsecond line")) shouldBe "IllegalStateException: first line"
        AssertionText.error(IllegalStateException()) shouldBe "IllegalStateException"
        AssertionText.error(IllegalStateException("x".repeat(1000))).length shouldBe
            "IllegalStateException: ".length + 300 + "… (700 more chars)".length
    }

    @Test
    fun `descriptions name what each assertion expects`() {
        AssertionText.describe(AssertionSpec.VisibleText("Salam", 5.seconds)) shouldBe "\"Salam\" visible within 5000 ms"
        AssertionText.describe(AssertionSpec.NotVisible(null, "#approve")) shouldBe "selector `#approve` not visible"
        AssertionText.describe(AssertionSpec.Oracle("/test/x", null, null, "a")) shouldBe "GET /test/x contains \"a\""
        AssertionText.describe(AssertionSpec.HttpStatus("/api/x", "POST", 409)) shouldBe "POST /api/x -> 409"
        AssertionText.describe(AssertionSpec.Count("#x", 2)) shouldBe "count of `#x` = 2"
        AssertionText.describe(AssertionSpec.LatencyMax(3.seconds)) shouldBe "latency <= 3000 ms"
        AssertionText.describe(AssertionSpec.OnlyOneSucceeds()) shouldBe "exactly one actor succeeds"
    }

    @Test
    fun `a race names the requests that decide it and its oracle condition`() {
        val approve = RequestPattern("POST", ".*/approve")

        AssertionText.describe(AssertionSpec.OnlyOneSucceeds(approve)) shouldBe "exactly one actor succeeds by `POST .*/approve`"
        AssertionText.describe(AssertionSpec.OnlyOneSucceeds(approve, OracleCondition("/test/tickets/7", "status", "approved"))) shouldBe
            "exactly one actor succeeds by `POST .*/approve` and GET /test/tickets/7 field `status` = \"approved\""
        AssertionText.describe(AssertionSpec.OnlyOneSucceeds(oracle = OracleCondition("/test/tickets/7"))) shouldBe
            "exactly one actor succeeds and GET /test/tickets/7 answers 2xx"
    }
}
