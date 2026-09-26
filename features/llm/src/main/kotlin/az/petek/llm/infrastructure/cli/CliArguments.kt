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

/**
 * Splits an argument template such as `-p --model {model} --system-prompt "{system}"` (`PETEK_LLM_ARGS`) into words
 * the way a shell would, but without a shell: spaces separate words, single or double quotes keep a word together, a
 * backslash escapes the next character. Nothing is expanded; placeholders are filled in later by [GenericCliProfile].
 */
object CliArguments {
    fun split(template: String): List<String> {
        val words = mutableListOf<String>()
        val current = StringBuilder()
        var inWord = false
        var quote: Char? = null
        var escaped = false
        for (char in template) {
            when {
                escaped -> {
                    current.append(char)
                    escaped = false
                }

                char == '\\' && quote != '\'' -> {
                    escaped = true
                    inWord = true
                }

                quote != null && char == quote -> {
                    quote = null
                }

                quote != null -> {
                    current.append(char)
                }

                char == '"' || char == '\'' -> {
                    quote = char
                    inWord = true
                }

                char.isWhitespace() -> {
                    if (inWord) words += current.toString().also { current.clear() }
                    inWord = false
                }

                else -> {
                    current.append(char)
                    inWord = true
                }
            }
        }
        require(quote == null) { "the argument template has an unclosed $quote quote" }
        if (escaped) current.append('\\')
        if (inWord) words += current.toString()
        return words
    }
}
