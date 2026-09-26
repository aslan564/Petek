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

package az.petek.campaign.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SourceLinesTest {
    private val lines =
        SourceLines(
            mapOf(
                SourceLines.ROOT to 3,
                "campaign" to 3,
                "campaign.roles" to 9,
                "steps" to 30,
                "steps[1]" to 40,
                "steps[1].assert[0]" to 44,
            ),
        )

    @Test
    fun `an exact path returns its line`() {
        lines.lineOf("steps[1].assert[0]") shouldBe 44
    }

    @Test
    fun `a path that was not recorded falls back to its closest ancestor`() {
        lines.lineOf("steps[1].assert[0].oracle.path") shouldBe 44
        lines.lineOf("steps[1].wait_for") shouldBe 40
        lines.lineOf("campaign.registration.invite") shouldBe 3
        lines.lineOf("target_profile.id_sources.x") shouldBe 3
    }

    @Test
    fun `no lines means no line`() {
        SourceLines.NONE.lineOf("campaign.testers") shouldBe null
    }

    @Test
    fun `parents strip one segment at a time down to the root`() {
        SourceLines.parentOf("steps[3].assert[1]") shouldBe "steps[3].assert"
        SourceLines.parentOf("steps[3].assert") shouldBe "steps[3]"
        SourceLines.parentOf("steps[3]") shouldBe "steps"
        SourceLines.parentOf("steps") shouldBe SourceLines.ROOT
        SourceLines.parentOf(SourceLines.ROOT) shouldBe null
        SourceLines.parentOf("campaign.budget.max_minutes") shouldBe "campaign.budget"
    }
}
