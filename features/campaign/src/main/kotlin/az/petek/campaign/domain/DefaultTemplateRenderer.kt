/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.campaign.domain

/**
 * Renders `{placeholder}` templates (see [Placeholder]). Only `{` + [Placeholder.NAME_PATTERN] + `}` is a placeholder,
 * so regex quantifiers like `\d{3}` or JSON braces stay literal. Substituted values are inserted verbatim and never
 * rendered again, so a value containing braces cannot inject another placeholder. A blank value counts as missing:
 * `/test/tickets/{last_id}` must fail rather than become `/test/tickets/`.
 */
class DefaultTemplateRenderer : TemplateRenderer {
    override fun render(
        template: String,
        context: TemplateContext,
    ): String {
        if ('{' !in template) return template
        val problems = mutableListOf<String>()
        val rendered =
            PLACEHOLDER.replace(template) { match ->
                val name = match.groupValues[1]
                resolve(name, context) ?: match.value.also { problems += problem(name, context) }
            }
        if (problems.isNotEmpty()) throw TemplateException(problems.joinToString("; "))
        return rendered
    }

    override fun placeholders(template: String): Set<String> =
        if ('{' !in template) emptySet() else PLACEHOLDER.findAll(template).mapTo(LinkedHashSet()) { it.groupValues[1] }

    private fun resolve(
        name: String,
        context: TemplateContext,
    ): String? =
        when (val placeholder = Placeholder.parse(name)) {
            Placeholder.LastId -> context.lastId
            is Placeholder.Self -> context.self[placeholder.field]
            is Placeholder.EventId -> context.eventIds[placeholder.event]
            null -> null
        }?.takeUnless { it.isBlank() }

    private fun problem(
        name: String,
        context: TemplateContext,
    ): String =
        when (val placeholder = Placeholder.parse(name)) {
            Placeholder.LastId -> {
                "Placeholder {$name} cannot be resolved: no object id has been emitted yet"
            }

            is Placeholder.Self -> {
                "Placeholder {$name} cannot be resolved: the tester has no '${placeholder.field}' " +
                    "(available: ${context.self.filterValues { it.isNotBlank() }.keys.sorted().joinToString(", ").ifEmpty { "none" }})"
            }

            is Placeholder.EventId -> {
                "Placeholder {$name} cannot be resolved: event '${placeholder.event}' has not been emitted yet"
            }

            null -> {
                "Unknown placeholder {$name}; supported forms: {last_id}, {self.<field>}, {event.<event>.id}"
            }
        }

    private companion object {
        val PLACEHOLDER = Regex("\\{(" + Placeholder.NAME_PATTERN.pattern + ")}")
    }
}
