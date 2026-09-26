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

package az.petek.app.logging

import kotlinx.coroutines.ThreadContextElement
import org.slf4j.MDC
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine context element that puts [values] into the SLF4J MDC of whichever thread runs the coroutine, and
 * restores that thread's previous MDC when the coroutine suspends or ends. Coroutines hop between threads, so a
 * plain `MDC.put` would leak one agent's `agent_id` into another agent's log lines.
 *
 * Keys mapped to null are removed for the coroutine, which lets an inner element (harness work inside a run)
 * drop `agent_id` while keeping `run_id`. Other MDC keys of the thread are kept.
 */
class MdcContext(
    val values: Map<String, String?>,
) : AbstractCoroutineContextElement(Key),
    ThreadContextElement<Map<String, String>?> {
    companion object Key : CoroutineContext.Key<MdcContext>

    override fun updateThreadContext(context: CoroutineContext): Map<String, String>? {
        val previous: Map<String, String>? = MDC.getCopyOfContextMap()
        val next = HashMap(previous.orEmpty())
        values.forEach { (key, value) -> if (value == null) next.remove(key) else next[key] = value }
        MDC.setContextMap(next)
        return previous
    }

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: Map<String, String>?,
    ) {
        if (oldState == null) MDC.clear() else MDC.setContextMap(oldState)
    }
}
