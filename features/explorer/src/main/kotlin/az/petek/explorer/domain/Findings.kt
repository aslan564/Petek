/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
