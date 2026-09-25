/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.explorer.domain

import az.petek.browser.domain.PageElement
import az.petek.browser.domain.PageSnapshot
import java.net.URI

/** An action found on a page, before it is merged into the model under its [ActionIds] id. */
data class ActionCandidate(
    val name: String,
    val kind: ActionKind,
    val selector: String,
    val httpMethod: String?,
    val httpPath: String?,
    val provenance: Provenance,
)

/** What code alone learned about one page (no LLM involved). Forms carry no evidence yet; the caller adds it. */
data class PageFacts(
    val forms: List<FormModel>,
    val actions: List<ActionCandidate>,
    val links: List<ScannedLink>,
    val testIds: List<String>,
    val accessibilityIssues: List<String>,
    val anomalies: List<String>,
)

/**
 * Extracts forms, fields, buttons, accessibility problems and leaked-error text from a page by code: the DOM scan
 * gives structure (which field belongs to which form, types, names, `required`), the snapshot's numbered element list
 * gives visibility and accessible names (the snapshot writes `data-petek-ref` into the DOM, so both are joined by ref).
 * When the page has no `<form>` elements at all, fields and the button after them in the element list are taken as one
 * form, which is how many single-page apps are built.
 */
object PageHeuristics {
    private val FIELD_ROLES = setOf("textbox", "searchbox", "combobox", "listbox", "spinbutton", "slider", "checkbox", "radio", "switch")
    private val NAMED_ROLES = setOf("button", "link", "menuitem", "tab")
    private const val MAX_ANOMALIES = 5
    private const val MAX_SNIPPET = 120

    private val ANOMALY_PATTERNS =
        listOf(
            Regex("\\[object Object]"),
            Regex("(?<![\\p{L}\\p{N}])(undefined|NaN)(?![\\p{L}\\p{N}])"),
            Regex("\\{\\{[^{}]{0,60}}}"),
            Regex("Traceback \\(most recent call last\\)"),
            Regex("\\b[A-Za-z0-9_.]*(Exception|TypeError|ReferenceError|SyntaxError)\\b"),
            Regex("\\bat [\\w.$<>]+\\([\\w.]+\\.(java|kt|js|ts|py):\\d+\\)"),
            Regex("(?i)internal server error"),
        )

    fun inspect(
        snapshot: PageSnapshot,
        document: ScannedDocument,
        pageUrl: URI,
    ): PageFacts {
        val elements = snapshot.elements.associateBy { it.ref }
        val page = Page(pageUrl, document.hasRefs, elements)
        val forms =
            document.forms.filter(page::isShown).map { page.form(it) }.ifEmpty {
                listOfNotNull(page.elementListForm(snapshot.elements))
            }
        val formActions = forms.mapNotNull(::actionOf)
        val buttonActions = document.looseButtons.filter { page.isShown(it.ref) }.mapNotNull(page::buttonAction)
        return PageFacts(
            forms = forms.map { it.first },
            actions = (formActions + buttonActions).distinctBy { it.selector },
            links = document.links,
            testIds = document.testIds,
            accessibilityIssues = accessibilityIssues(snapshot.elements, document),
            anomalies = anomalies(snapshot.visibleText),
        )
    }

    private fun actionOf(form: Pair<FormModel, String?>): ActionCandidate? {
        val (model, submitName) = form
        val selector = model.submitSelector ?: return null
        return ActionCandidate(
            name = submitName?.takeIf { it.isNotBlank() } ?: model.purpose,
            kind = model.kind,
            selector = selector,
            httpMethod = model.method.takeIf { it.isNotBlank() },
            httpPath = model.actionPath,
            provenance = Provenance.OBSERVED,
        )
    }

    /** Page context shared by the extraction steps. */
    private class Page(
        private val url: URI,
        private val hasRefs: Boolean,
        private val elements: Map<Int, PageElement>,
    ) {
        private val origin = runCatching { SiteOrigin.of(url) }.getOrNull()

        fun isShown(ref: Int?): Boolean = !hasRefs || (ref != null && ref in elements)

        fun isShown(form: ScannedForm): Boolean = form.fields.any { isShown(it.ref) } || form.buttons.any { isShown(it.ref) }

        /**
         * The form as a model plus the accessible name of its submit button. What the submit button really sends
         * decides: its `formaction`/`formmethod` replace the form's, and a hidden `_method` replaces POST.
         */
        fun form(form: ScannedForm): Pair<FormModel, String?> {
            val submit = form.buttons.filter { isShown(it.ref) }.firstOrNull { it.type == "submit" || it.type == "image" }
            val action = submit?.formAction ?: form.action
            val sent = form.copy(action = action, method = sentMethod(form, submit))
            val destination = destination(action)
            val actionPattern = (destination as? Destination.SameSite)?.pattern
            val classification = FormClassifier.classify(sent, actionPattern, offSite = destination == Destination.OtherSite)
            val scopedByAction = actionPattern?.takeIf { !UrlPatterns.hasId(it) && form.action == action }?.let { form.action }
            val fields = form.fields.filter { isShown(it.ref) }.map { field(it, scopedByAction) }
            val model =
                FormModel(
                    purpose = classification.purpose,
                    kind = classification.kind,
                    fields = fields,
                    submitSelector = submit?.let(::buttonSelector),
                    method = sent.method,
                    actionPath = actionPattern,
                    provenance = Provenance.OBSERVED,
                    evidence = emptyList(),
                )
            return model to submit?.let { elementName(it.ref) ?: it.text }
        }

        private fun sentMethod(
            form: ScannedForm,
            submit: ScannedButton?,
        ): String {
            val method = submit?.formMethod ?: form.method
            return if (method == "POST") form.methodOverride ?: method else method
        }

        /** A form assembled from the element list: the fields in order, then the first button after the last field. */
        fun elementListForm(list: List<PageElement>): Pair<FormModel, String?>? {
            val fields = list.filter { it.role in FIELD_ROLES && it.enabled }
            if (fields.isEmpty()) return null
            val lastField = fields.maxOf { it.ref }
            val submit = list.filter { it.role == "button" && it.ref > lastField }.minByOrNull { it.ref }
            val scanned =
                ScannedForm(
                    action = null,
                    method = "",
                    id = null,
                    testId = null,
                    fields = fields.map(::scannedFieldOf),
                    buttons = listOfNotNull(submit?.let { ScannedButton(it.tag, "submit", it.name, null, null, it.testId, it.ref) }),
                )
            val classification = FormClassifier.classify(scanned, null)
            val model =
                FormModel(
                    purpose = classification.purpose,
                    kind = classification.kind,
                    fields = fields.map(::elementField),
                    submitSelector = submit?.let(::elementSelector),
                    method = "",
                    actionPath = null,
                    provenance = Provenance.OBSERVED,
                    evidence = emptyList(),
                )
            return model to submit?.name
        }

        fun buttonAction(button: ScannedButton): ActionCandidate? {
            val words = listOfNotNull(button.testId, button.id, button.name, button.text).joinToString(" ")
            val kind = FormClassifier.buttonKind(words) ?: return null
            return ActionCandidate(
                name = elementName(button.ref) ?: button.text.ifBlank { button.testId ?: kind.name.lowercase() },
                kind = kind,
                selector = buttonSelector(button),
                httpMethod = null,
                httpPath = null,
                provenance = Provenance.OBSERVED,
            )
        }

        /**
         * Where a form with [action] is sent: this site (the page itself without an action), another site, or
         * nowhere a crawler can tell (`javascript:` actions of forms that scripts submit).
         */
        private fun destination(action: String?): Destination {
            val resolved = (if (action == null) url else UrlPatterns.resolve(url, action)) ?: return Destination.Unknown
            if (origin == null) return Destination.Unknown
            return if (origin.contains(resolved)) Destination.SameSite(UrlPatterns.of(resolved)) else Destination.OtherSite
        }

        private fun field(
            field: ScannedField,
            scopeAction: String?,
        ): FieldModel {
            val accessibleName = elementName(field.ref)
            return FieldModel(
                label = field.label ?: field.ariaLabel ?: accessibleName ?: field.placeholder ?: field.name.orEmpty(),
                name = field.name ?: field.id ?: field.testId ?: "",
                type = field.type,
                required = field.required,
                testId = field.testId,
                selector = fieldSelector(field, scopeAction, accessibleName),
                options = field.options.take(MAX_OPTIONS),
            )
        }

        private fun fieldSelector(
            field: ScannedField,
            scopeAction: String?,
            accessibleName: String?,
        ): String {
            field.testId?.let { return Selectors.testId(it) }
            field.id?.let { return Selectors.id(it) }
            field.name?.let { name ->
                val plain = Selectors.name(field.tag, name)
                return if (scopeAction != null) "form[action=\"${Selectors.escape(scopeAction)}\"] $plain" else plain
            }
            val role = elements[field.ref]?.role
            if (role != null && accessibleName != null) return Selectors.role(role, accessibleName)
            return "${field.tag}[type=\"${Selectors.escape(field.type)}\"]"
        }

        private fun buttonSelector(button: ScannedButton): String {
            button.testId?.let { return Selectors.testId(it) }
            button.id?.let { return Selectors.id(it) }
            val name = elementName(button.ref) ?: button.text
            return if (name.isNotBlank()) Selectors.role("button", name) else "${button.tag}[type=\"${Selectors.escape(button.type)}\"]"
        }

        private fun elementName(ref: Int?): String? = ref?.let(elements::get)?.name?.takeIf { it.isNotBlank() }
    }

    private sealed interface Destination {
        data class SameSite(
            val pattern: String,
        ) : Destination

        data object OtherSite : Destination

        data object Unknown : Destination
    }

    private const val MAX_OPTIONS = 20

    private fun scannedFieldOf(element: PageElement): ScannedField =
        ScannedField(
            tag = element.tag,
            type = typeOf(element),
            name = null,
            id = null,
            testId = element.testId,
            label = element.name.takeIf { it.isNotBlank() },
            ariaLabel = null,
            placeholder = null,
            required = false,
            ref = element.ref,
            options = emptyList(),
            autocomplete = null,
        )

    private fun elementField(element: PageElement): FieldModel =
        FieldModel(
            label = element.name,
            name = element.testId ?: Slugs.of(element.name),
            type = typeOf(element),
            required = false,
            testId = element.testId,
            selector = elementSelector(element),
        )

    private fun elementSelector(element: PageElement): String =
        element.testId?.let(Selectors::testId)
            ?: element.name.takeIf { it.isNotBlank() }?.let { Selectors.role(element.role, it) }
            ?: element.tag

    private fun typeOf(element: PageElement): String =
        when (element.role) {
            "combobox", "listbox" -> {
                "select"
            }

            "checkbox", "radio" -> {
                element.role
            }

            "spinbutton" -> {
                "number"
            }

            "searchbox" -> {
                "search"
            }

            else -> {
                if (element.value == "******") {
                    "password"
                } else if (element.tag == "textarea") {
                    "textarea"
                } else {
                    "text"
                }
            }
        }

    private fun accessibilityIssues(
        elements: List<PageElement>,
        document: ScannedDocument,
    ): List<String> {
        val unnamedFields = elements.filter { it.role in FIELD_ROLES && it.name.isBlank() }
        val unnamedControls = elements.filter { it.role in NAMED_ROLES && it.name.isBlank() }
        return buildList {
            if (unnamedFields.isNotEmpty()) {
                add(
                    "${unnamedFields.size} form field(s) without an accessible name: ${unnamedFields.joinToString(
                        ", ",
                        transform = ::describe,
                    )}",
                )
            }
            if (unnamedControls.isNotEmpty()) {
                add(
                    "${unnamedControls.size} button(s) or link(s) without an accessible name: ${unnamedControls.joinToString(
                        ", ",
                        transform = ::describe,
                    )}",
                )
            }
            if (document.imagesWithoutAlt > 0) add("${document.imagesWithoutAlt} image(s) without alt text")
        }
    }

    /** True when an accessibility issue list mentions unnamed form fields (the more serious kind). */
    fun hasUnnamedFields(issues: List<String>): Boolean = issues.any { it.contains("form field(s)") }

    private fun describe(element: PageElement): String =
        element.testId?.let { "${element.role} testid=$it" } ?: "${element.role} <${element.tag}>"

    private fun anomalies(visibleText: String): List<String> =
        visibleText
            .lineSequence()
            .flatMap { line -> ANOMALY_PATTERNS.mapNotNull { pattern -> pattern.find(line)?.let { line.trim().take(MAX_SNIPPET) } } }
            .distinct()
            .take(MAX_ANOMALIES)
            .toList()
}
