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

package az.petek.app.panel.explorer

import az.petek.campaign.domain.DefaultActorExpressionParser
import az.petek.campaign.domain.RoleQuota
import az.petek.dashboard.domain.RoleSplit
import az.petek.explorer.application.ScenarioRequest
import az.petek.explorer.application.ScenarioSettings

/**
 * The frame of every campaign draft the panel generates from a site model, the same for the preview on the
 * "Kəşfiyyat" screen and for the draft stored in the catalog: the owner's team from the instruction form (one admin,
 * the managers, the employees) when it is one, else the generator's small team; with the owner's departments when every
 * one of them can be named in an actor expression (else the generator's defaults). A site without companies takes only
 * the tester count from the form ([ScenarioRequest.testers]).
 */
internal object DraftSettings {
    fun of(
        departments: List<String>,
        team: RoleSplit? = null,
    ): ScenarioSettings {
        val names = departments.map { it.trim() }
        val usable =
            names.isNotEmpty() &&
                names.none { name -> name.isEmpty() || name.any { it in DefaultActorExpressionParser.RESERVED_CHARS } } &&
                names.map(String::lowercase).toSet().size == names.size
        val frame = if (usable) ScenarioSettings(departments = names) else ScenarioSettings()
        val owners = team?.takeIf { it.admins == 1 && it.managers >= 0 && it.employees >= 0 } ?: return frame
        return frame.copy(team = RoleQuota(admin = 1, manager = owners.managers, employee = owners.employees))
    }
}
