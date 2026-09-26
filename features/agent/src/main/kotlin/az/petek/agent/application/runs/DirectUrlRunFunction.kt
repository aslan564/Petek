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

package az.petek.agent.application.runs

import az.petek.agent.application.RunFunction
import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.AgentRuntime
import az.petek.agent.domain.FailureReason
import az.petek.agent.domain.StepContext

/**
 * `direct_url`: a tester opens the address of an object that is not theirs (`path`, usually `/notes/{event.x.id}`)
 * straight from the address bar, as someone guessing URLs would (docs/PLAN.md Faza 13). Passes when the site refuses:
 * the page answers 401, 403 or 404, sends the tester to another page, or does not show the object's `text` (its
 * title, when given). Fails with `access_not_refused` when the object is shown, with the page's screenshot.
 */
internal class DirectUrlRunFunction(
    private val engine: RunEngine,
) : RunFunction {
    override val name: String = RunFunctions.DIRECT_URL

    override suspend fun execute(
        runtime: AgentRuntime,
        args: Map<String, String>,
        step: StepContext,
    ): ActionOutcome =
        engine.execute(name, runtime, step) {
            val path =
                args["path"]?.trim()?.takeIf { it.startsWith("/") }
                    ?: throw RunFailure(FailureReason.MISSING_PREREQUISITE, "direct_url needs a path argument starting with '/'")
            val text = args["text"]?.trim()?.ifEmpty { null }
            val status = probe("GET $path as ${runtime.identity.agentId}", { runtime.session.request("GET", path).status }) { true }
            if (status in REFUSED) return@execute succeeded("$path answered $status: refused.")
            openUrl(path)
            val landed = currentUrl()
            val shown = if (text != null) runtime.session.isTextVisible(text) else status in SUCCESS
            if (!shown || !landed.contains(path.substringBefore('?'))) {
                return@execute succeeded("$path was not shown to ${runtime.identity.agentId} (answered $status, now on $landed).")
            }
            captureScreenshot()
            failed(
                FailureReason.ACCESS_NOT_REFUSED,
                "$path showed ${text?.let {
                    "'$it'"
                } ?: "the object"} to ${runtime.identity.agentId}, who does not own it (answered $status).",
            )
        }

    private companion object {
        val REFUSED = setOf(401, 403, 404)
        val SUCCESS = 200..299
    }
}
