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
