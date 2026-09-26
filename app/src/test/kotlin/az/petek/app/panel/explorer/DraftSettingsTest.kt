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
