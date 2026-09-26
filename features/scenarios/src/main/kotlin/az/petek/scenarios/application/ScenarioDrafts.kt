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
import az.petek.campaign.domain.ValidationIssue
import az.petek.core.time.HarnessClock
import az.petek.scenarios.domain.NewScenarioVersion
import az.petek.scenarios.domain.ScenarioIdGenerator
import az.petek.scenarios.domain.ScenarioInvalidException
import az.petek.scenarios.domain.ScenarioRepository
import az.petek.scenarios.domain.ScenarioSource
import az.petek.scenarios.domain.ScenarioValidator
import az.petek.scenarios.domain.ScenarioVersion

/**
 * The one way a new version enters the catalog, shared by the catalog and triage: the text must load and pass the
 * campaign validator, and a version derived from a parent keeps the parent's name (it is the same scenario).
 */
internal class ScenarioDrafts(
    private val repository: ScenarioRepository,
    private val validator: ScenarioValidator,
    private val clock: HarnessClock,
    private val ids: ScenarioIdGenerator,
) {
    /** Loads and validates [yaml] under [fileName]; throws [ScenarioInvalidException] with every issue. */
    suspend fun check(
        yaml: String,
        fileName: String,
    ): Campaign = validator.check(yaml, fileName).validCampaign()

    suspend fun create(
        yaml: String,
        source: ScenarioSource,
        parent: ScenarioVersion?,
        note: String,
        fileName: String? = null,
    ): ScenarioVersion {
        val campaign = check(yaml, fileName ?: parent?.fileName ?: DEFAULT_FILE_NAME)
        return store(yaml, campaign, source, parent, note)
    }

    /** Stores text already checked into [campaign]. */
    suspend fun store(
        yaml: String,
        campaign: Campaign,
        source: ScenarioSource,
        parent: ScenarioVersion?,
        note: String,
    ): ScenarioVersion {
        val name = campaign.settings.name
        if (parent != null && parent.name != name) {
            throw ScenarioInvalidException(
                listOf(
                    ValidationIssue(
                        campaign.sourceLines.lineOf("campaign.name"),
                        "a new version of '${parent.name}' must keep its name, but the text names '$name'",
                    ),
                ),
            )
        }
        return repository.add(NewScenarioVersion(ids.versionId(), name, yaml, source, parent?.id, note.trim(), clock.now().wall))
    }

    companion object {
        /** Checked file name when nothing better is known; a YAML without `campaign.name` is then called `scenario`. */
        const val DEFAULT_FILE_NAME: String = "scenario.yaml"
    }
}
