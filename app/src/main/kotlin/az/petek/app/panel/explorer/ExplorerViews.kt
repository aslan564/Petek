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

import az.petek.dashboard.domain.ActionNodeView
import az.petek.dashboard.domain.ExplorationFindingView
import az.petek.dashboard.domain.FieldNodeView
import az.petek.dashboard.domain.FindingSeverity
import az.petek.dashboard.domain.FormNodeView
import az.petek.dashboard.domain.ModelChangeKind
import az.petek.dashboard.domain.ModelChangeView
import az.petek.dashboard.domain.PageNodeView
import az.petek.dashboard.domain.RealtimeView
import az.petek.dashboard.domain.SiteModelDiffView
import az.petek.dashboard.domain.SiteModelView
import az.petek.dashboard.domain.TestIdeaView
import az.petek.explorer.domain.ActionModel
import az.petek.explorer.domain.ExplorationFinding
import az.petek.explorer.domain.FieldModel
import az.petek.explorer.domain.FormModel
import az.petek.explorer.domain.GateMaps
import az.petek.explorer.domain.PageModel
import az.petek.explorer.domain.Severity
import az.petek.explorer.domain.SiteKinds
import az.petek.explorer.domain.SiteModel
import az.petek.explorer.domain.SiteModelDiff
import az.petek.explorer.domain.TestPatternLibrary
import az.petek.dashboard.domain.Provenance as ViewProvenance
import az.petek.explorer.domain.Provenance as ModelProvenance

/**
 * Maps what the explorer learned ([SiteModel], findings, test ideas, model diffs) into the panel's views. Pure: the
 * explorer never depends on the panel, the panel never on the explorer; only the composition root sees both.
 */
internal object ExplorerViews {
    /** Ideas shown per exploration; the library orders them by importance. */
    const val MAX_IDEAS = 30

    fun model(model: SiteModel): SiteModelView {
        val patterns = model.pages.associate { it.id to it.urlPattern }
        return SiteModelView(
            version = model.version,
            pages = model.pages.map { page -> page(page, model.actions.filter { it.pageId == page.id }) },
            realtime =
                model.realtime.map { observation ->
                    RealtimeView(
                        transport = observation.transport.name.lowercase(),
                        detail = observation.detail,
                        pages = observation.pages.map { patterns[it] ?: it }.sorted(),
                    )
                },
            kind = if (model.pages.isEmpty()) null else ExplorerTexts.kind(SiteKinds.of(model).kind),
            kindReason = if (model.pages.isEmpty()) null else SiteKinds.of(model).reason,
            gate = if (model.pages.isEmpty()) emptyList() else ExplorerTexts.gate(GateMaps.of(model)),
        )
    }

    fun page(
        page: PageModel,
        actions: List<ActionModel>,
    ): PageNodeView =
        PageNodeView(
            id = page.id,
            urlPattern = page.urlPattern,
            title = page.title,
            purpose = page.purpose,
            reachableBy = page.reachableBy.sorted(),
            provenance = provenance(page.provenance),
            forms = page.forms.map(::form),
            actions = actions.map(::action),
        )

    fun action(action: ActionModel): ActionNodeView =
        ActionNodeView(
            id = action.id,
            name = action.name,
            kind = action.kind.name,
            provenance = provenance(action.provenance),
            allowedRoles = action.allowedRoles.sorted(),
            forbiddenRoles = action.forbiddenRoles.sorted(),
            triggersRealtime = action.triggersRealtime,
        )

    fun finding(finding: ExplorationFinding): ExplorationFindingView =
        ExplorationFindingView(
            kind = finding.kind.name,
            severity =
                when (finding.severity) {
                    Severity.LOW -> FindingSeverity.LOW
                    Severity.MEDIUM -> FindingSeverity.MEDIUM
                    Severity.HIGH -> FindingSeverity.HIGH
                },
            pageUrl = finding.pageUrl,
            detail = finding.detail,
            artifactIds = finding.evidence,
        )

    /**
     * The model's test ideas, most important first, with the owner's [instructions] raising matching actions. The
     * panel ranks ideas 1 (do first) to 3; the library's 0..100 priorities fall into those tiers.
     */
    fun ideas(
        model: SiteModel,
        instructions: String?,
    ): List<TestIdeaView> =
        TestPatternLibrary().ideas(model, instructions).take(MAX_IDEAS).map { idea ->
            val action = model.action(idea.actionId)
            val name = action?.name?.ifBlank { null } ?: idea.actionId
            TestIdeaView(
                pattern = idea.pattern.name,
                action = name,
                rationale = ExplorerTexts.rationale(idea, name, action?.kind),
                priority = tier(idea.priority),
            )
        }

    /** 1 for the ideas to write first (priority 60 and above), 2 for the next ones (45..59), 3 for the rest. */
    fun tier(priority: Int): Int =
        when {
            priority >= FIRST_TIER -> 1
            priority >= SECOND_TIER -> 2
            else -> 3
        }

    /** One line per added, removed or changed page, form, field and action, pages first. */
    fun diff(diff: SiteModelDiff): SiteModelDiffView =
        SiteModelDiffView(
            fromVersion = diff.fromVersion,
            toVersion = diff.toVersion,
            changes =
                buildList {
                    diff.addedPages.forEach { add(ModelChangeView(ModelChangeKind.ADDED, PAGE, it.urlPattern, it.title)) }
                    diff.removedPages.forEach { add(ModelChangeView(ModelChangeKind.REMOVED, PAGE, it.urlPattern, it.title)) }
                    diff.changedPages.forEach { page ->
                        if (page.changes.isNotEmpty()) {
                            add(ModelChangeView(ModelChangeKind.CHANGED, PAGE, page.urlPattern, page.changes.joinToString("; ")))
                        }
                        page.addedForms.forEach {
                            add(
                                ModelChangeView(ModelChangeKind.ADDED, FORM, formName(page.urlPattern, it), fields(it)),
                            )
                        }
                        page.removedForms.forEach {
                            add(ModelChangeView(ModelChangeKind.REMOVED, FORM, formName(page.urlPattern, it), fields(it)))
                        }
                        page.changedForms.forEach { form ->
                            val name = "${page.urlPattern} · ${form.key}"
                            if (form.changes.isNotEmpty()) {
                                add(
                                    ModelChangeView(ModelChangeKind.CHANGED, FORM, name, form.changes.joinToString("; ")),
                                )
                            }
                            form.addedFields.forEach {
                                add(
                                    ModelChangeView(ModelChangeKind.ADDED, FIELD, "$name · ${fieldName(it)}", it.type),
                                )
                            }
                            form.removedFields.forEach {
                                add(
                                    ModelChangeView(ModelChangeKind.REMOVED, FIELD, "$name · ${fieldName(it)}", it.type),
                                )
                            }
                            form.changedFields.forEach {
                                add(ModelChangeView(ModelChangeKind.CHANGED, FIELD, "$name · ${it.key}", it.changes.joinToString("; ")))
                            }
                        }
                    }
                    diff.addedActions.forEach { add(ModelChangeView(ModelChangeKind.ADDED, ACTION, it.name, actionDetail(it))) }
                    diff.removedActions.forEach { add(ModelChangeView(ModelChangeKind.REMOVED, ACTION, it.name, actionDetail(it))) }
                    diff.changedActions.forEach {
                        add(ModelChangeView(ModelChangeKind.CHANGED, ACTION, it.actionId, it.changes.joinToString("; ")))
                    }
                },
        )

    private fun form(form: FormModel): FormNodeView =
        FormNodeView(
            purpose = form.purpose,
            provenance = provenance(form.provenance),
            fields = form.fields.map { FieldNodeView(fieldName(it), it.type, it.required) },
        )

    private fun fieldName(field: FieldModel): String = field.label.ifBlank { field.name.ifBlank { field.testId ?: field.selector } }

    private fun formName(
        pattern: String,
        form: FormModel,
    ): String = "$pattern · ${form.purpose.ifBlank { form.key }}"

    private fun fields(form: FormModel): String? = form.fields.takeIf { it.isNotEmpty() }?.joinToString { fieldName(it) }

    private fun actionDetail(action: ActionModel): String = "${ExplorerTexts.kind(action.kind)} · ${action.pageId}"

    private fun provenance(provenance: ModelProvenance): ViewProvenance =
        when (provenance) {
            ModelProvenance.OBSERVED -> ViewProvenance.OBSERVED
            ModelProvenance.INFERRED -> ViewProvenance.INFERRED
        }

    private const val FIRST_TIER = 60
    private const val SECOND_TIER = 45
    private const val PAGE = "PAGE"
    private const val FORM = "FORM"
    private const val FIELD = "FIELD"
    private const val ACTION = "ACTION"
}
