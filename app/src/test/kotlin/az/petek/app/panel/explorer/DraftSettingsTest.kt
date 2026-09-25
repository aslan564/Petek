/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.panel.explorer

import az.petek.explorer.application.ScenarioSettings
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DraftSettingsTest {
    @Test
    fun `the owner's departments frame the draft when actor expressions can name every one of them`() {
        DraftSettings.of(listOf(" Satış ", "İnsan resursları")).departments shouldBe listOf("Satış", "İnsan resursları")
        DraftSettings.of(listOf("IT", "R&D [lab]")) shouldBe ScenarioSettings()
        DraftSettings.of(listOf("IT", "it")) shouldBe ScenarioSettings()
        DraftSettings.of(listOf("IT", " ")) shouldBe ScenarioSettings()
        DraftSettings.of(emptyList()) shouldBe ScenarioSettings()
    }
}
