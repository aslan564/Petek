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

package az.petek.orchestration.application

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Rethrows when this coroutine itself is cancelled (budget, abort, the caller), so [error] can only be handled as a
 * failure when it is not our own cancellation. A [CancellationException] caught while this coroutine is still active
 * came from a callee — typically its own `withTimeout` — and must count as that callee's failure: rethrowing it would
 * silently end the step (or the whole run) as if someone had cancelled it.
 */
internal suspend fun rethrowIfCancelled(error: Exception) {
    if (error is CancellationException) currentCoroutineContext().ensureActive()
}
