/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.reporting.domain

import az.petek.evidence.domain.FindingClass
import az.petek.evidence.domain.FindingRecord

/**
 * The three shelves of the customer report (Faza 20): what the site should fix, what Pətək itself could not do (a
 * tester that got stuck, an inbox that could not be read), and what a person should look at. Without a triage the
 * finding class decides; the panel's triage (system bug / model gap / scenario error) sorts each surprise more exactly.
 */
enum class Shelf {
    SITE_BUG,
    TOOL_GAP,
    INVESTIGATE,
    ;

    companion object {
        fun of(finding: FindingRecord): Shelf =
            when (finding.findingClass) {
                FindingClass.BACKEND, FindingClass.DELIVERY_UI -> SITE_BUG
                FindingClass.AGENT_FAILURE -> TOOL_GAP
                FindingClass.INVESTIGATE, FindingClass.FLAKY -> INVESTIGATE
            }
    }
}
