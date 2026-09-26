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

package az.petek.dashboard.domain

import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.EventReceipt
import az.petek.evidence.domain.EventRecord
import az.petek.evidence.domain.FindingRecord
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict
import az.petek.orchestration.domain.AgentState
import az.petek.orchestration.domain.RunOutcome

/**
 * The words of the timeline. The page is Azerbaijani, so the few words the harness adds around recorded values are
 * too; the values themselves (actions, event names, expectations) stay exactly as recorded. Every text is one line
 * and clipped, so a runaway detail cannot bloat the page.
 */
internal object TimelineTexts {
    const val ACTION_CHARS = 160
    const val TEXT_CHARS = 300
    const val SOURCE_CHARS = 500

    private val WHITESPACE = Regex("\\s+")

    fun step(record: StepRecord): String {
        val detail = record.detail?.takeIf { record.status != StepStatus.PASSED && it.isNotBlank() }
        return line(if (detail == null) record.action else "${record.action} — $detail")
    }

    fun stepFailure(record: StepRecord): String = line(record.detail?.takeIf { it.isNotBlank() } ?: record.action, TEXT_CHARS)

    fun event(record: EventRecord): String = line(record.name + (record.objectId?.let { " · obyekt $it" } ?: ""))

    fun receipt(
        record: EventReceipt,
        eventName: String,
    ): String =
        if (record.received) {
            line(eventName + (record.latencyMs?.let { " · $it ms" } ?: ""))
        } else {
            line("$eventName · görünmədi")
        }

    fun assertion(record: AssertionRecord): String {
        val observed = record.observed?.takeIf { record.verdict == Verdict.FAILED }?.let { " → $it" } ?: ""
        val latency = record.latencyMs?.let { " · $it ms" } ?: ""
        val note = record.note?.takeIf { record.verdict != Verdict.PASSED && it.isNotBlank() }?.let { " · $it" } ?: ""
        return line("${record.type}: ${record.expected}$observed$latency$note")
    }

    fun finding(record: FindingRecord): String = line("${record.findingClass.name}: ${record.note}")

    fun stateChange(
        state: AgentState,
        reason: String?,
    ): String = line(STATE_WORDS.getValue(state) + (reason?.let { " — $it" } ?: ""))

    fun scenarioStepStarted(scenarioStep: String): String = line("Addım başladı: $scenarioStep")

    fun runEnded(outcome: RunOutcome?): String =
        when (outcome) {
            RunOutcome.PASSED -> "Run bitdi: keçdi"
            RunOutcome.FAILED -> "Run bitdi: uğursuz"
            RunOutcome.ABORTED -> "Run dayandırıldı"
            null -> "Run yarımçıq qalıb"
        }

    /** [text] on one line (every run of whitespace, newlines included, becomes one space), cut to [max] characters. */
    fun line(
        text: String,
        max: Int = TEXT_CHARS,
    ): String {
        val single = text.trim().replace(WHITESPACE, " ")
        return if (single.length <= max) single else single.take(max - 1) + "…"
    }

    private val STATE_WORDS =
        mapOf(
            AgentState.IDLE to "boşdur",
            AgentState.WORKING to "işləyir",
            AgentState.WAITING to "gözləyir",
            AgentState.BLOCKED to "bloklandı",
            AgentState.FAILED to "sıradan çıxdı",
            AgentState.DONE to "bitirdi",
        )
}
