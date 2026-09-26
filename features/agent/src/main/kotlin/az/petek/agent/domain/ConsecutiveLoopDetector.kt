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

package az.petek.agent.domain

/**
 * Flags a loop when the model picks the very same action (same tool, same arguments) [threshold] times in a row.
 * Waiting and reading count too: three identical `wait_text` calls mean the agent is stuck, not patient.
 * One detector belongs to one `do` execution; create a fresh one per execution.
 */
class ConsecutiveLoopDetector(
    private val threshold: Int = 3,
) : LoopDetector {
    private var last: AgentAction? = null
    private var repeats = 0

    init {
        require(threshold >= 2) { "A loop needs at least 2 identical actions, threshold was $threshold" }
    }

    @Synchronized
    override fun register(action: AgentAction): Boolean {
        repeats = if (action == last) repeats + 1 else 1
        last = action
        return repeats >= threshold
    }

    @Synchronized
    override fun reset() {
        last = null
        repeats = 0
    }
}
