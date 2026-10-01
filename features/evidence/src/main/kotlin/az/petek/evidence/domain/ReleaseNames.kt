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

package az.petek.evidence.domain

/**
 * What a release name ([RunRecord.release]: `petek run --release`, the panel, MCP `run_campaign`) may be: 1 to [MAX]
 * letters, digits and `. _ - +`, and never a word a comparison baseline already means (`previous`, or a run id such as
 * `run_7`), or `petek compare --baseline` could never choose that release.
 */
object ReleaseNames {
    const val MAX = 64

    /** The baseline `petek compare` takes by default: the latest earlier run of the scenario. */
    const val PREVIOUS = "previous"

    /** How a run id looks where a baseline is named. */
    val RUN_ID = Regex("run_[A-Za-z0-9_-]+")

    private val NAME = Regex("[\\p{L}\\p{N}._+\\-]{1,$MAX}")

    fun isValid(name: String): Boolean = NAME.matches(name) && !name.equals(PREVIOUS, ignoreCase = true) && !RUN_ID.matches(name)
}
