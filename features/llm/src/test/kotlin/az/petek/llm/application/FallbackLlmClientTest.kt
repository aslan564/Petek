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

package az.petek.llm.application

import az.petek.llm.LlmTestData
import az.petek.llm.OutcomeLlmClient
import az.petek.llm.domain.LlmClient
import az.petek.llm.domain.LlmException
import az.petek.llm.domain.LlmProviderKey
import az.petek.llm.domain.LlmRequest
import az.petek.llm.domain.LlmResponse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class FallbackLlmClientTest {
    private val request = LlmTestData.request()

    private class Named(
        override val provider: LlmProviderKey,
        private val answer: suspend (LlmRequest) -> LlmResponse,
    ) : LlmClient {
        var calls = 0
        override val model: String = "m"

        override suspend fun complete(request: LlmRequest): LlmResponse {
            calls++
            return answer(request)
        }
    }

    @Test
    fun `an unavailable provider is passed over once and later calls start with the one that works`() =
        runTest {
            val broken = Named(LlmProviderKey.CODEX_CLI) { throw LlmException.Unavailable("not logged in") }
            val working = Named(LlmProviderKey.GEMINI_CLI) { LlmTestData.response() }
            val fallback =
                FallbackLlmClient(
                    listOf(
                        FallbackLlmClient.Candidate(broken.provider) {
                            broken
                        },
                        FallbackLlmClient.Candidate(working.provider) { working },
                    ),
                )

            fallback.complete(request)
            fallback.complete(request)

            broken.calls shouldBe 1
            working.calls shouldBe 2
            fallback.provider shouldBe LlmProviderKey.GEMINI_CLI
            fallback.skipped.single() shouldContain "codex-cli: not logged in"
        }

    @Test
    fun `other failures belong to the provider that answered and are not a reason to switch`() =
        runTest {
            val slow = OutcomeLlmClient(outcomes = listOf({ throw LlmException.Transient("overloaded") }))
            val other = Named(LlmProviderKey.GEMINI_CLI) { LlmTestData.response() }
            val fallback =
                FallbackLlmClient(
                    listOf(FallbackLlmClient.Candidate(slow.provider) { slow }, FallbackLlmClient.Candidate(other.provider) { other }),
                )

            shouldThrow<LlmException.Transient> { fallback.complete(request) }

            other.calls shouldBe 0
            fallback.skipped.shouldBeEmpty()
        }

    @Test
    fun `when every provider is unavailable the last reason is thrown`() =
        runTest {
            val first = Named(LlmProviderKey.CODEX_CLI) { throw LlmException.Unavailable("first") }
            val last = Named(LlmProviderKey.GEMINI_CLI) { throw LlmException.Unavailable("last") }
            val fallback =
                FallbackLlmClient(
                    listOf(FallbackLlmClient.Candidate(first.provider) { first }, FallbackLlmClient.Candidate(last.provider) { last }),
                )

            shouldThrow<LlmException.Unavailable> { fallback.complete(request) }.message shouldBe "last"
        }

    @Test
    fun `no provider means every call says how to set one up`() =
        runTest {
            val none = UnavailableLlmClient("nothing found")

            val error = shouldThrow<LlmException.Unavailable> { none.complete(request) }

            error.message.orEmpty() shouldContain "No AI provider: nothing found."
            error.message.orEmpty() shouldContain "PETEK_LLM_BIN"
            none.provider shouldBe LlmProviderKey.NONE
        }
}
