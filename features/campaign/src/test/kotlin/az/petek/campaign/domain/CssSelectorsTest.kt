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

class CssSelectorsTest {
    @Test
    fun `plain CSS is plain, whatever its combinators and pseudo-classes`() {
        listOf(
            "[data-testid=\"server-clock\"]",
            "header .online-count, aside > .ticker",
            "li:has(time):not(:visited)",
            "a[href^=\"/text=\"]:nth-child(2)",
            "#news-ticker ~ div",
        ).forEach { CssSelectors.playwrightOnly(it) shouldBe null }
    }

    @Test
    fun `Playwright's engines, chains and pseudo-classes are named`() {
        mapOf(
            "text=Bu gün" to "text=",
            " role=button[name=\"Qəbul edirəm\"]" to "role=",
            "XPATH=//div" to "xpath=",
            "//div[@id='x']" to "//",
            "internal:testid=[data-testid=\"x\"s]" to "internal:",
            "div.banner >> nth=0" to ">>",
            "li:has-text(\"Bu gün\")" to ":has-text(",
            "button:visible" to ":visible",
            "td:right-of(:text(\"Ad\"))" to ":text(",
        ).forEach { (selector, token) -> CssSelectors.playwrightOnly(selector) shouldBe token }
    }
}
