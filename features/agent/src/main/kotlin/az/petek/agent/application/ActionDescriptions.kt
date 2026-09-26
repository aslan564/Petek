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

package az.petek.agent.application

import az.petek.agent.domain.AgentAction
import az.petek.browser.domain.PageSnapshot

private const val MAX_QUOTED_CHARS = 120

/**
 * Readable form of an action for evidence and for the history replayed to the model, e.g.
 * `click [12] "Elan yarat"` or `type [5] "{self.password}" +submit`. Typed text is shown as the model wrote it,
 * so placeholders stay placeholders.
 */
internal fun AgentAction.describe(snapshot: PageSnapshot?): String =
    when (this) {
        is AgentAction.Navigate -> "navigate $url"
        is AgentAction.Click -> "click ${element(ref, snapshot)}"
        is AgentAction.Type -> "type ${element(ref, snapshot, named = false)} ${quote(text)}" + if (submit) " +submit" else ""
        is AgentAction.Select -> "select ${element(ref, snapshot, named = false)} ${quote(option)}"
        is AgentAction.ReadText -> "read_text $selector"
        is AgentAction.WaitText -> "wait_text ${quote(text)} ${timeout.inWholeSeconds}s"
        AgentAction.GetEmailCode -> "get_email_code"
        AgentAction.GetPhoneCode -> "get_phone_code"
        is AgentAction.Done -> "done success=$success ${quote(summary)}" + (objectId?.let { " object_id=$it" } ?: "")
        is AgentAction.ReportProblem -> "report_problem ${kind.key} ${quote(note)}"
    }

private fun element(
    ref: Int,
    snapshot: PageSnapshot?,
    named: Boolean = true,
): String {
    val element = snapshot?.elements?.firstOrNull { it.ref == ref }
    return when {
        element == null -> "[$ref]"
        named && element.name.isNotBlank() -> "[$ref] ${quote(element.name)}"
        else -> "[$ref]" + (element.name.takeIf { it.isNotBlank() }?.let { " (${it.clip(MAX_QUOTED_CHARS)})" } ?: "")
    }
}

internal fun quote(text: String): String = "\"" + text.replace("\n", " ").clip(MAX_QUOTED_CHARS) + "\""
