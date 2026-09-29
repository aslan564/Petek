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

package az.petek.app.di

import az.petek.llm.domain.LlmClient
import az.petek.llm.domain.LlmProviderKey
import az.petek.llm.domain.LlmRequest
import az.petek.llm.domain.LlmResponse

/**
 * The AI the container's agents think with, which the panel may switch while it runs (Faza 23: the owner chooses
 * another AI on the setup screen). A call goes to the client in force when it starts; [switchTo] makes the next calls
 * go to another one. The one switched away from is closed by whoever created it (the container's resources).
 */
internal class SwitchableLlmClient(
    initial: LlmClient,
) : LlmClient {
    @Volatile
    private var current: LlmClient = initial

    override val provider: LlmProviderKey get() = current.provider
    override val model: String get() = current.model

    override suspend fun complete(request: LlmRequest): LlmResponse = current.complete(request)

    fun switchTo(next: LlmClient) {
        current = next
    }
}
