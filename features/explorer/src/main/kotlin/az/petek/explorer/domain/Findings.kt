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

package az.petek.explorer.domain

import az.petek.core.ids.ArtifactId
import az.petek.core.ids.FindingId

enum class FindingKind {
    /** A same-site link answers 404 or 410. */
    BROKEN_LINK,

    /** A page answers with an error status (5xx, or another 4xx than 401/403/404/410), or fails to load. */
    HTTP_ERROR,

    /** A script error on the page. Needs console access that the browser port does not offer yet. */
    CONSOLE_ERROR,

    /** A page took longer than the configured threshold to load (measured by the harness clock). */
    SLOW_PAGE,

    /** Form fields, buttons or links without an accessible name, images without alternative text. */
    ACCESSIBILITY,

    /** Visible text that looks like a leaked error: stack traces, `undefined`, `NaN`, raw template braces. */
    UNEXPECTED_UI,
}

enum class Severity { LOW, MEDIUM, HIGH }

/**
 * A problem the explorer noticed while learning the site. Findings are recorded by code from observations, never
 * judged by the LLM; [evidence] links the screenshot, DOM or HTTP answer they are based on. [role] is the viewpoint
 * (`anonymous` or a role name) and [pageUrl] the page without query string.
 */
data class ExplorationFinding(
    val id: FindingId,
    val kind: FindingKind,
    val severity: Severity,
    val pageUrl: String,
    val detail: String,
    val role: String,
    val evidence: List<ArtifactId>,
)
