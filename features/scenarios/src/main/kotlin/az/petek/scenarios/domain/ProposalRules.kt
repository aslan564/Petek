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

package az.petek.scenarios.domain

import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.CampaignSettings

/**
 * What a triage proposal may change. A proposal fixes the site knowledge (`target_profile`) or the steps, never who
 * tests against what: the campaign name, target, testers, seed, names, roles, departments and registration split
 * must stay as they are. Only `budget` and `on_fail` may change, because a too-small step budget is a scenario bug.
 *
 * The settings are compared as loaded. A target override (`PETEK_TARGET`) replaces `campaign.target` in the loaded
 * settings, so the target is also compared as it is written in the file: a proposal that edits the target line is
 * refused whatever the override hides.
 */
object ProposalRules {
    fun violations(
        parent: Campaign,
        parentYaml: String,
        child: Campaign,
        childYaml: String,
    ): List<String> {
        val before = parent.settings
        val after = child.settings
        if (before.name != after.name) {
            return listOf("the change renames the scenario from '${before.name}' to '${after.name}'")
        }
        val changed = PROTECTED.filter { (_, value) -> value(before) != value(after) }.map { it.first }.toMutableList()
        if (TARGET !in changed && declaredTarget(parent, parentYaml) != declaredTarget(child, childYaml)) changed.add(0, TARGET)
        return if (changed.isEmpty()) {
            emptyList()
        } else {
            listOf("the change touches campaign settings a proposal must keep: ${changed.joinToString(", ")}")
        }
    }

    /** `campaign.target` as written in [yaml] (the value, or the whole line when it cannot be isolated); null when absent. */
    internal fun declaredTarget(
        campaign: Campaign,
        yaml: String,
    ): String? {
        val line = campaign.sourceLines.byPath[TARGET_PATH] ?: return null
        val text = yaml.lines().getOrNull(line - 1) ?: return null
        return TARGET_VALUE.find(text)?.groupValues?.get(1) ?: text.trim()
    }

    private const val TARGET = "target"
    private const val TARGET_PATH = "campaign.target"

    /** `target: <value>` in block (`  target: https://…  # note`) or flow (`{…, target: "https://…", …}`) style. */
    private val TARGET_VALUE = Regex("(?:^|[\\s{,])target\\s*:\\s*(\"[^\"]*\"|'[^']*'|[^\\s,}#]+)")

    private val PROTECTED: List<Pair<String, (CampaignSettings) -> Any?>> =
        listOf(
            TARGET to { it.target },
            "testers" to { it.testers },
            "seed" to { it.seed },
            "names" to { it.names },
            "roles" to { it.roles },
            "departments" to { it.departments },
            "registration" to { it.registration },
        )
}
