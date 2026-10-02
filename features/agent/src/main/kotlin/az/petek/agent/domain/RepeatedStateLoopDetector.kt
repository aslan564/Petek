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
 * Flags a loop when the model picks the very same action (same tool, same arguments) on the same page [threshold]
 * times within its last [window] decisions (Faza 24.8). The page is part of the key: element refs are numbered afresh
 * in every snapshot, so the same `click [7]` on three different pages (a "Next" button while paging) is progress, not
 * a loop; and a cycle such as A-B-A-B-A on a page that never changes is caught, which counting identical actions in a
 * row misses. Waiting and reading count too: three identical `wait_text` calls on the same page mean the agent is
 * stuck, not patient. One detector belongs to one `do` execution; create a fresh one per execution.
 */
class RepeatedStateLoopDetector(
    private val threshold: Int = 3,
    private val window: Int = 6,
) : LoopDetector {
    private val recent = ArrayDeque<Pair<AgentAction, String>>()

    init {
        require(threshold >= 2) { "A loop needs at least 2 identical actions, threshold was $threshold" }
        require(window >= threshold) { "The window must hold the threshold's repeats, window was $window" }
    }

    @Synchronized
    override fun register(
        action: AgentAction,
        page: String,
    ): Boolean {
        val key = action to page
        recent.addLast(key)
        if (recent.size > window) recent.removeFirst()
        return recent.count { it == key } >= threshold
    }

    @Synchronized
    override fun reset() {
        recent.clear()
    }
}
