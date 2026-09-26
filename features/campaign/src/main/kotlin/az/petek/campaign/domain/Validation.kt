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

package az.petek.campaign.domain

import az.petek.core.error.PetekException

data class ValidationIssue(
    val line: Int?,
    val message: String,
) {
    override fun toString(): String = if (line != null) "line $line: $message" else message
}

class CampaignValidationException(
    val issues: List<ValidationIssue>,
) : PetekException("Campaign is invalid:\n" + issues.joinToString("\n") { "  - $it" })

/** Parses actor expressions; throws [CampaignValidationException] with the offending text on bad input. */
interface ActorExpressionParser {
    fun parse(
        raw: String,
        line: Int? = null,
    ): ActorExpression

    fun parseList(
        items: List<String>,
        line: Int? = null,
    ): ActorExpression
}

/**
 * Cross-field rules: role quota sums to testers, registration quota sums to non-admins and invites at least every
 * manager (managers always join by invitation), departments non-empty,
 * unique step ids, every `wait_for` refers to an event emitted by an earlier step, `only_one_succeeds` only on
 * `parallel` steps with 2+ actors, known `run` functions, templates only use known placeholders, and the target profile's
 * flows, overlays, API prefix and the campaign's pacing are well-formed.
 */
interface CampaignValidator {
    fun validate(
        campaign: Campaign,
        knownRunFunctions: Set<String>,
    ): List<ValidationIssue>
}
