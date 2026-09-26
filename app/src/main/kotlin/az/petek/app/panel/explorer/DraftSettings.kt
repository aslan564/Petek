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
import az.petek.explorer.application.ScenarioSettings

/**
 * The frame of every campaign draft the panel generates from a site model, the same for the preview on the
 * "Kəşfiyyat" screen and for the draft stored in the catalog: the generator's small team, with the owner's departments
 * when every one of them can be named in an actor expression (else the generator's defaults).
 */
internal object DraftSettings {
    fun of(departments: List<String>): ScenarioSettings {
        val names = departments.map { it.trim() }
        val usable =
            names.isNotEmpty() &&
                names.none { name -> name.isEmpty() || name.any { it in DefaultActorExpressionParser.RESERVED_CHARS } } &&
                names.map(String::lowercase).toSet().size == names.size
        return if (usable) ScenarioSettings(departments = names) else ScenarioSettings()
    }
}
