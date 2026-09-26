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

import az.petek.llm.domain.LlmClient
import az.petek.llm.domain.LlmException
import az.petek.llm.domain.LlmProviderKey
import az.petek.llm.domain.LlmRequest
import az.petek.llm.domain.LlmResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * Tries the AI providers found on the machine in order and stays with the first one that works, so Pətək is tied to no
 * single vendor: when a provider is [LlmException.Unavailable] (not installed, not logged in, arguments refused), the
 * call moves on to the next [candidates] entry and every later call starts there. Other failures (rate limits, timeouts,
 * bad output) belong to the provider that answered and are passed on as they are. Clients are opened on first use.
 */
class FallbackLlmClient(
    private val candidates: List<Candidate>,
) : LlmClient,
    AutoCloseable {
    /** One provider and how to open its client. */
    class Candidate(
        val provider: LlmProviderKey,
        val open: () -> LlmClient,
    )

    init {
        require(candidates.isNotEmpty()) { "a fallback needs at least one provider" }
    }

    private val current = AtomicInteger(0)
    private val opened = ConcurrentHashMap<Int, LlmClient>()
    private val passed = CopyOnWriteArrayList<String>()

    /** Why earlier providers were passed over, e.g. `codex-cli: Codex CLI cannot answer: not logged in`. */
    val skipped: List<String> get() = passed.toList()

    override val provider: LlmProviderKey get() = client(current.get()).provider
    override val model: String get() = client(current.get()).model

    override suspend fun complete(request: LlmRequest): LlmResponse {
        while (true) {
            val index = current.get()
            try {
                return client(index).complete(request)
            } catch (e: LlmException.Unavailable) {
                if (index + 1 >= candidates.size) throw e
                if (current.compareAndSet(index, index + 1)) {
                    passed += "${candidates[index].provider}: ${e.message}"
                    logger.warn {
                        "AI provider ${candidates[index].provider} is unavailable, trying ${candidates[index + 1].provider}: ${e.message}"
                    }
                }
            }
        }
    }

    override fun close() {
        opened.values.forEach { (it as? AutoCloseable)?.close() }
    }

    private fun client(index: Int): LlmClient = opened.computeIfAbsent(index) { candidates[it].open() }
}
