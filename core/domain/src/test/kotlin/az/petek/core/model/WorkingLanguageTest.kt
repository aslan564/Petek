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

package az.petek.core.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class WorkingLanguageTest {
    @Test
    fun `auto follows the owner's own text and never forces a language`() {
        WorkingLanguage.of(null) shouldBe WorkingLanguage.AUTO
        WorkingLanguage.of(" Auto ") shouldBe WorkingLanguage.AUTO
        WorkingLanguage.AUTO.isAuto shouldBe true
        val rule = WorkingLanguage.AUTO.rule("purpose and unknowns")
        rule shouldContain "Write purpose and unknowns in the language the owner's own text"
        rule shouldContain "language of the page"
        rule shouldNotContain "in Azerbaijani"
    }

    @Test
    fun `a named language is used whatever the page or the owner wrote`() {
        val english = WorkingLanguage.of("English")
        english.isAuto shouldBe false
        english.toString() shouldBe "English"
        english.rule("rationale and summary") shouldBe
            "Write rationale and summary in English, whatever language the page or the owner's text uses."
        shouldThrow<IllegalArgumentException> { WorkingLanguage("  ") }
    }
}
