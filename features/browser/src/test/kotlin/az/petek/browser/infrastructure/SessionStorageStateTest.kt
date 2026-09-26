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

package az.petek.browser.infrastructure

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SessionStorageStateTest {
    @TempDir
    lateinit var dir: Path

    private fun state(text: String): Path = Files.writeString(dir.resolve("state.json"), text)

    @Test
    fun `entries go into their origin's entry, or a new one, and Playwright's own keys stay`() {
        val file =
            state(
                """{"cookies":[{"name":"sid","value":"1"}],"origins":[{"origin":"https://a.example",""" +
                    """"localStorage":[{"name":"theme","value":"dark"}]}]}""",
            )

        SessionStorageState.write(
            file,
            mapOf(
                "https://a.example" to listOf(listOf("token", "t-1")),
                "https://sso.example" to listOf(listOf("state", "x")),
                "https://empty.example" to emptyList(),
            ),
        )

        SessionStorageState.read(file) shouldBe
            mapOf("https://a.example" to listOf(listOf("token", "t-1")), "https://sso.example" to listOf(listOf("state", "x")))
        val text = Files.readString(file)
        text shouldContain """"cookies":[{"name":"sid","value":"1"}]"""
        text shouldContain """"localStorage":[{"name":"theme","value":"dark"}]"""
        text.contains("empty.example") shouldBe false
    }

    @Test
    fun `a state without sessionStorage, or a file that is not a state, has no entries`() {
        SessionStorageState.read(state("""{"cookies":[],"origins":[{"origin":"https://a.example","localStorage":[]}]}""")) shouldBe
            emptyMap()
        SessionStorageState.read(state("not json")) shouldBe emptyMap()
        SessionStorageState.read(dir.resolve("absent.json")) shouldBe emptyMap()
    }

    @Test
    fun `nothing to add leaves the file as Playwright wrote it`() {
        val written = """{"cookies":[],"origins":[]}"""
        val file = state(written)

        SessionStorageState.write(file, mapOf("https://a.example" to emptyList()))

        Files.readString(file) shouldBe written
    }
}
