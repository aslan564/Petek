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

/**
 * The answer to the one question the setup page asks when no configuration names a site to test (AGENTS.md rule 12:
 * the owner is asked, and nothing starts until the answer comes): [configure] takes the address the owner typed,
 * writes the configuration and opens the panel for it, or says why the address cannot be used.
 */
fun interface SiteSetup {
    suspend fun configure(target: String): SetupAnswer
}

sealed interface SetupAnswer {
    /** The site is written down and the panel runs at [panel]. */
    data class Ready(
        val panel: String,
    ) : SetupAnswer

    /** The address cannot be used; [message] tells the owner why, in their words. */
    data class Refused(
        val message: String,
    ) : SetupAnswer
}
