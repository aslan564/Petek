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

package az.petek.llm.domain

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Told how an AI call of the coroutine it rides in goes: waiting for one of the slots the testers share
 * (`PETEK_LLM_CONCURRENCY`), then at the provider. Whoever must tell a slow queue from a stuck caller puts one into the
 * caller's coroutine context (the runner's inactivity watchdog does, for a tester's action), and the call path reports
 * to it; without one in the context nothing is reported. A call that never waits reports only [callStarted] and
 * [callEnded].
 *
 * Every method is cheap, never blocks and never throws; each start is followed by its end exactly once, also when the
 * wait or the call is cancelled or fails.
 */
abstract class LlmCallObserver : AbstractCoroutineContextElement(Key) {
    /** The call waits for a free slot: what it waits for is the other testers' calls, not anything of its own. */
    abstract fun slotWaitStarted()

    /** The wait that [slotWaitStarted] began is over: the call got its slot, or it was cancelled while waiting. */
    abstract fun slotWaitEnded()

    /** The call goes to the provider now. */
    abstract fun callStarted()

    /** The provider answered, failed, or the call was cancelled. */
    abstract fun callEnded()

    companion object Key : CoroutineContext.Key<LlmCallObserver>
}
