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

import java.net.URI

/** One changed attribute, shown as `field: before -> after`. */
data class Change(
    val field: String,
    val before: String,
    val after: String,
) {
    override fun toString(): String = "$field: $before -> $after"
}

data class FieldChange(
    val key: String,
    val changes: List<Change>,
)

data class FormChange(
    val key: String,
    val addedFields: List<FieldModel>,
    val removedFields: List<FieldModel>,
    val changedFields: List<FieldChange>,
    val changes: List<Change>,
)

data class PageChange(
    val urlPattern: String,
    val changes: List<Change>,
    val addedForms: List<FormModel>,
    val removedForms: List<FormModel>,
    val changedForms: List<FormChange>,
)

data class ActionChange(
    val actionId: String,
    val changes: List<Change>,
)

/**
 * What changed on a site between two model versions (docs/PLAN.md Faza 7 "fərq kəşfiyyatı"): pages are matched by
 * URL pattern, forms by their key within the page, fields by name, actions by id. Only observed facts are compared,
 * so the diff is not noise from the LLM wording differently: page purposes, load times and link counts are ignored,
 * and an action's name counts only when both versions observed it (rather than the LLM naming it). Role sets are
 * compared only over the roles both explorations used, and `triggersRealtime` only when both versions observed it,
 * so exploring with fewer roles or without a trial touch does not show up as a change of the site.
 */
data class SiteModelDiff(
    val target: URI,
    val fromVersion: Int,
    val toVersion: Int,
    val addedPages: List<PageModel>,
    val removedPages: List<PageModel>,
    val changedPages: List<PageChange>,
    val addedActions: List<ActionModel>,
    val removedActions: List<ActionModel>,
    val changedActions: List<ActionChange>,
) {
    val isEmpty: Boolean
        get() =
            addedPages.isEmpty() && removedPages.isEmpty() && changedPages.isEmpty() &&
                addedActions.isEmpty() && removedActions.isEmpty() && changedActions.isEmpty()

    companion object {
        fun between(
            before: SiteModel,
            after: SiteModel,
        ): SiteModelDiff {
            val oldPages = before.pages.associateBy { it.urlPattern }
            val newPages = after.pages.associateBy { it.urlPattern }
            val oldActions = before.actions.associateBy { it.id }
            val newActions = after.actions.associateBy { it.id }
            val roles = before.roles.map { it.name }.toSet() intersect after.roles.map { it.name }.toSet()
            return SiteModelDiff(
                target = after.target,
                fromVersion = before.version,
                toVersion = after.version,
                addedPages = after.pages.filter { it.urlPattern !in oldPages },
                removedPages = before.pages.filter { it.urlPattern !in newPages },
                changedPages = after.pages.mapNotNull { page -> oldPages[page.urlPattern]?.let { pageChange(it, page, roles) } },
                addedActions = after.actions.filter { it.id !in oldActions },
                removedActions = before.actions.filter { it.id !in newActions },
                changedActions = after.actions.mapNotNull { action -> oldActions[action.id]?.let { actionChange(it, action, roles) } },
            )
        }

        private fun pageChange(
            before: PageModel,
            after: PageModel,
            roles: Set<String>,
        ): PageChange? {
            val changes =
                listOfNotNull(
                    change("title", before.title, after.title),
                    change("reachableBy", before.reachableBy.shared(roles), after.reachableBy.shared(roles)),
                    change("testIds", before.testIds.sorted(), after.testIds.sorted()),
                )
            val oldForms = before.forms.associateBy { it.key }
            val newForms = after.forms.associateBy { it.key }
            val added = after.forms.filter { it.key !in oldForms }
            val removed = before.forms.filter { it.key !in newForms }
            val changed = after.forms.mapNotNull { form -> oldForms[form.key]?.let { formChange(it, form) } }
            if (changes.isEmpty() && added.isEmpty() && removed.isEmpty() && changed.isEmpty()) return null
            return PageChange(after.urlPattern, changes, added, removed, changed)
        }

        private fun formChange(
            before: FormModel,
            after: FormModel,
        ): FormChange? {
            val changes =
                listOfNotNull(
                    change("kind", before.kind.name, after.kind.name),
                    change("method", before.method, after.method),
                    change("actionPath", before.actionPath.orEmpty(), after.actionPath.orEmpty()),
                )
            val oldFields = before.fields.associateBy { it.key }
            val newFields = after.fields.associateBy { it.key }
            val added = after.fields.filter { it.key !in oldFields }
            val removed = before.fields.filter { it.key !in newFields }
            val changed = after.fields.mapNotNull { field -> oldFields[field.key]?.let { fieldChange(it, field) } }
            if (changes.isEmpty() && added.isEmpty() && removed.isEmpty() && changed.isEmpty()) return null
            return FormChange(after.key, added, removed, changed, changes)
        }

        private fun fieldChange(
            before: FieldModel,
            after: FieldModel,
        ): FieldChange? {
            val changes =
                listOfNotNull(
                    change("label", before.label, after.label),
                    change("type", before.type, after.type),
                    change("required", before.required.toString(), after.required.toString()),
                    change("selector", before.selector, after.selector),
                    change("options", before.options, after.options),
                )
            return if (changes.isEmpty()) null else FieldChange(after.key, changes)
        }

        private fun actionChange(
            before: ActionModel,
            after: ActionModel,
            roles: Set<String>,
        ): ActionChange? {
            val bothObserved = before.provenance == Provenance.OBSERVED && after.provenance == Provenance.OBSERVED
            val changes =
                listOfNotNull(
                    if (bothObserved) change("name", before.name, after.name) else null,
                    change("kind", before.kind.name, after.kind.name),
                    change("pageId", before.pageId, after.pageId),
                    change("selector", before.selector, after.selector),
                    change("allowedRoles", before.allowedRoles.shared(roles), after.allowedRoles.shared(roles)),
                    change("forbiddenRoles", before.forbiddenRoles.shared(roles), after.forbiddenRoles.shared(roles)),
                    if (before.triggersRealtime != null && after.triggersRealtime != null) {
                        change("triggersRealtime", before.triggersRealtime.toString(), after.triggersRealtime.toString())
                    } else {
                        null
                    },
                )
            return if (changes.isEmpty()) null else ActionChange(after.id, changes)
        }

        private fun Set<String>.shared(roles: Set<String>): List<String> = filter { it in roles }.sorted()

        private fun change(
            field: String,
            before: String,
            after: String,
        ): Change? = if (before == after) null else Change(field, before, after)

        private fun change(
            field: String,
            before: List<String>,
            after: List<String>,
        ): Change? = if (before == after) null else Change(field, before.joinToString(", ", "[", "]"), after.joinToString(", ", "[", "]"))
    }
}
