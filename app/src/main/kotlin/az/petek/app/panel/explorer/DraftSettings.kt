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
