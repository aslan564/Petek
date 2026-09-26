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

package az.petek.scenarios.application

import az.petek.campaign.domain.Campaign
import az.petek.scenarios.domain.ProposalRules
import az.petek.scenarios.domain.ScenarioValidator
import az.petek.scenarios.domain.YamlEdit
import az.petek.scenarios.domain.YamlEdits

/**
 * Decides in code whether a proposed change is usable: the edits apply to the text, the result loads and passes the
 * campaign validator exactly as a run would, and it changes nothing a proposal must keep ([ProposalRules]).
 */
internal class ProposalCheck(
    private val validator: ScenarioValidator,
) {
    sealed interface Outcome {
        data class Usable(
            val yaml: String,
        ) : Outcome

        data class Unusable(
            val reason: String,
        ) : Outcome
    }

    /**
     * Applies [edits] to [base] and checks the result. [parent] (loaded from [parentYaml]) is the version the proposal
     * was made for; [base] is [parentYaml] itself or a triage draft already built on it.
     */
    suspend fun check(
        base: String,
        edits: List<YamlEdit>,
        parent: Campaign,
        parentYaml: String,
        fileName: String,
    ): Outcome {
        val yaml =
            when (val applied = YamlEdits.apply(base, edits)) {
                is YamlEdits.Result.Failed -> return Outcome.Unusable(applied.reason)
                is YamlEdits.Result.Applied -> applied.yaml
            }
        val check = validator.check(yaml, fileName)
        val child =
            check.campaign?.takeIf { check.valid }
                ?: return Outcome.Unusable(
                    "the changed scenario does not pass the campaign validator: " + check.issues.joinToString("; ") { it.toString() },
                )
        val violations = ProposalRules.violations(parent, parentYaml, child, yaml)
        return if (violations.isEmpty()) Outcome.Usable(yaml) else Outcome.Unusable(violations.joinToString("; "))
    }
}
