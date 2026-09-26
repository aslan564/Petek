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

package az.petek.llm.infrastructure.cli

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test

class CliArgumentsTest {
    @Test
    fun `words are split on spaces, quotes keep a word together and a backslash escapes`() {
        CliArguments.split("""-p --system-prompt "{system} and more" --name 'a b' x\ y""") shouldContainExactly
            listOf("-p", "--system-prompt", "{system} and more", "--name", "a b", "x y")
    }

    @Test
    fun `an empty template has no words and an empty quoted word is kept`() {
        CliArguments.split("   ").shouldBeEmpty()
        CliArguments.split("""--tools "" -p""") shouldContainExactly listOf("--tools", "", "-p")
    }

    @Test
    fun `an unclosed quote is refused`() {
        shouldThrow<IllegalArgumentException> { CliArguments.split("""-p "open""") }
    }
}
