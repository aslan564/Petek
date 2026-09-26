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

package az.petek.faketarget.web

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SafeNextTest {
    @Test
    fun `same-site paths are kept`() {
        safeNext("/") shouldBe "/"
        safeNext("/tickets/t1?tab=history") shouldBe "/tickets/t1?tab=history"
        safeNext("/announcements%2Fa1") shouldBe "/announcements%2Fa1"
    }

    @Test
    fun `anything a browser could turn into another origin is refused`() {
        listOf(
            null,
            "",
            "tickets",
            "https://evil.example/",
            "//evil.example",
            "/\\evil.example",
            "/\t/evil.example",
            "/\n/evil.example",
            "/\r/evil.example",
            " //evil.example",
            "/ /evil.example",
            "/\u0000/evil.example",
        ).forEach { safeNext(it) shouldBe null }
    }
}
