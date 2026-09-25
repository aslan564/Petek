package az.petek.campaign.domain

/**
 * Values available to `{placeholder}` templates in steps and assertions.
 * `{last_id}`, `{self.email}`, `{self.name}`, `{self.agent_id}`, `{self.department}`, `{self.role}`, `{event.<name>.id}`.
 */
data class TemplateContext(
    val lastId: String?,
    val self: Map<String, String>,
    val eventIds: Map<String, String>,
)

/** Pure template rendering. Unknown or unresolvable placeholders fail loudly instead of producing wrong URLs. */
interface TemplateRenderer {
    fun render(
        template: String,
        context: TemplateContext,
    ): String

    /** Placeholder names used in [template], e.g. `last_id`, `self.email`. */
    fun placeholders(template: String): Set<String>
}

class TemplateException(
    message: String,
) : az.petek.core.error.PetekException(message)
