package az.petek.verification.testing

import az.petek.campaign.domain.TemplateContext
import az.petek.campaign.domain.TemplateException
import az.petek.campaign.domain.TemplateRenderer

/**
 * Minimal stand-in for the campaign renderer (implemented elsewhere): resolves `{last_id}`, `{self.<key>}` and
 * `{event.<name>.id}`, and fails loudly on anything unknown, like the real contract requires.
 */
class FakeTemplateRenderer : TemplateRenderer {
    private val placeholder = Regex("""\{([a-z_]+(?:\.[a-z0-9_]+)*)}""")

    override fun render(
        template: String,
        context: TemplateContext,
    ): String =
        placeholder.replace(template) { match ->
            val name = match.groupValues[1]
            resolve(name, context) ?: throw TemplateException("Unknown or unresolved placeholder {$name}")
        }

    override fun placeholders(template: String): Set<String> = placeholder.findAll(template).map { it.groupValues[1] }.toSet()

    private fun resolve(
        name: String,
        context: TemplateContext,
    ): String? =
        when {
            name == "last_id" -> {
                context.lastId
            }

            name.startsWith("self.") -> {
                context.self[name.removePrefix("self.")]
            }

            name.startsWith("event.") && name.endsWith(".id") -> {
                context.eventIds[name.removePrefix("event.").removeSuffix(".id")]
            }

            else -> {
                null
            }
        }
}
