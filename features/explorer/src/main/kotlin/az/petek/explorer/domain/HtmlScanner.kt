/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.explorer.domain

/** What [HtmlScanner] found in a page's DOM. */
data class ScannedDocument(
    val links: List<ScannedLink>,
    val forms: List<ScannedForm>,
    /** Fields and buttons outside any `<form>` (single-page apps often have no forms at all). */
    val looseFields: List<ScannedField>,
    val looseButtons: List<ScannedButton>,
    /** Every `data-testid` on the page, in document order, without duplicates. */
    val testIds: List<String>,
    val imagesWithoutAlt: Int,
    /**
     * Whether the DOM carries snapshot refs (`data-petek-ref`, written by the browser snapshot). When it does,
     * an element without a ref was not visible.
     */
    val hasRefs: Boolean,
) {
    companion object {
        val EMPTY = ScannedDocument(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 0, false)
    }
}

data class ScannedLink(
    val href: String,
    val text: String,
    val ref: Int?,
)

/**
 * A `<form>`. [methodOverride] is the value of a hidden `_method` field (`DELETE`, `PATCH`, …), which frameworks such
 * as Rails and Laravel use to send other methods than POST from a plain form.
 */
data class ScannedForm(
    val action: String?,
    val method: String,
    val id: String?,
    val testId: String?,
    val fields: List<ScannedField>,
    val buttons: List<ScannedButton>,
    val methodOverride: String? = null,
)

/** An editable control: `input` (not hidden, not a button), `select` or `textarea`. */
data class ScannedField(
    val tag: String,
    val type: String,
    val name: String?,
    val id: String?,
    val testId: String?,
    val label: String?,
    val ariaLabel: String?,
    val placeholder: String?,
    val required: Boolean,
    val ref: Int?,
    /** Labels of the selectable options of a `select` (options without a value, i.e. placeholders, are left out). */
    val options: List<String>,
    val autocomplete: String?,
)

/**
 * A `button` or an `input` of type submit/button/reset/image. [formAction] and [formMethod] (`formaction`,
 * `formmethod`) replace the form's own action and method when this button submits it.
 */
data class ScannedButton(
    val tag: String,
    val type: String,
    val text: String,
    val name: String?,
    val id: String?,
    val testId: String?,
    val ref: Int?,
    val formAction: String? = null,
    val formMethod: String? = null,
)

/**
 * A small, dependency-free reader of serialized DOM (the browser's `domSnapshot`): it finds links, forms with their
 * fields, labels and buttons, test ids and images without alternative text. It is not a full HTML parser; it relies
 * on the well-formed markup a browser serializes (quoted attributes, closed elements) and skips the content of
 * `script`, `style`, `template` and `noscript`. Character references in text and attributes are decoded.
 */
object HtmlScanner {
    const val REF_ATTRIBUTE = "data-petek-ref"
    private const val METHOD_OVERRIDE = "_method"

    private val TAG =
        Regex("""<!--[\s\S]*?-->|<![^>]*>|<(/?)([A-Za-z][A-Za-z0-9:_-]*)((?:[^>"']|"[^"]*"|'[^']*')*)>""")
    private val ATTRIBUTE = Regex("""([^\s"'<>/=]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?""")
    private val RAW_TEXT = setOf("script", "style", "template", "noscript")
    private val BUTTON_INPUTS = setOf("submit", "button", "reset", "image")
    private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[A-Za-z]+);")
    private val NAMED_ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")
    private val WHITESPACE = Regex("\\s+")

    fun scan(html: String): ScannedDocument {
        val state = ScanState()
        var index = 0
        while (index < html.length) {
            val match = TAG.find(html, index) ?: break
            state.text(html.substring(index, match.range.first))
            index = match.range.last + 1
            val name = match.groupValues[2].lowercase()
            if (name.isEmpty()) continue
            if (match.groupValues[1] == "/") {
                state.close(name)
                continue
            }
            state.open(name, attributes(match.groupValues[3]))
            if (name in RAW_TEXT) {
                val end = html.indexOf("</$name", index, ignoreCase = true)
                index = if (end < 0) html.length else end
            }
        }
        if (index < html.length) state.text(html.substring(index))
        return state.result()
    }

    /** Decodes character references (`&amp;`, `&#39;`, `&#x4B;`); unknown named references stay as written. */
    fun decode(text: String): String {
        if ('&' !in text) return text
        return ENTITY.replace(text) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") -> codePoint(body.drop(2).toIntOrNull(16)) ?: match.value
                body.startsWith("#") -> codePoint(body.drop(1).toIntOrNull()) ?: match.value
                else -> NAMED_ENTITIES[body] ?: match.value
            }
        }
    }

    private fun codePoint(value: Int?): String? =
        value
            ?.takeIf {
                Character.isValidCodePoint(it) && it != 0
            }?.let { String(Character.toChars(it)) }

    private fun collapse(text: String): String = WHITESPACE.replace(text, " ").trim()

    private fun attributes(raw: String): Map<String, String> =
        ATTRIBUTE
            .findAll(raw)
            .associate { match ->
                val value = match.groups[2]?.value ?: match.groups[3]?.value ?: match.groups[4]?.value ?: ""
                match.groupValues[1].lowercase() to decode(value)
            }

    private class FieldBuilder(
        val tag: String,
        val type: String,
        val attributes: Map<String, String>,
    ) {
        var label: String? = null
        val options = mutableListOf<String>()

        fun build(labelsFor: Map<String, String>): ScannedField {
            val id = attributes["id"]?.takeIf { it.isNotBlank() }
            return ScannedField(
                tag = tag,
                type = type,
                name = attributes["name"]?.takeIf { it.isNotBlank() },
                id = id,
                testId = attributes["data-testid"]?.takeIf { it.isNotBlank() },
                label = label?.takeIf { it.isNotBlank() } ?: id?.let(labelsFor::get),
                ariaLabel = attributes["aria-label"]?.let(::collapse)?.takeIf { it.isNotBlank() },
                placeholder = attributes["placeholder"]?.let(::collapse)?.takeIf { it.isNotBlank() },
                required = "required" in attributes || attributes["aria-required"] == "true",
                ref = attributes[REF_ATTRIBUTE]?.toIntOrNull(),
                options = options.toList(),
                autocomplete = attributes["autocomplete"]?.takeIf { it.isNotBlank() },
            )
        }
    }

    private class FormBuilder(
        val attributes: Map<String, String>,
    ) {
        val fields = mutableListOf<FieldBuilder>()
        val buttons = mutableListOf<ScannedButton>()
        var methodOverride: String? = null
    }

    private class ScanState {
        private val links = mutableListOf<ScannedLink>()
        private val forms = mutableListOf<FormBuilder>()
        private val looseFields = mutableListOf<FieldBuilder>()
        private val looseButtons = mutableListOf<ScannedButton>()
        private val testIds = LinkedHashSet<String>()
        private val labelsFor = mutableMapOf<String, String>()
        private var imagesWithoutAlt = 0
        private var hasRefs = false

        private var form: FormBuilder? = null
        private var anchor: Pair<Map<String, String>, StringBuilder>? = null
        private var label: Triple<String?, StringBuilder, MutableList<FieldBuilder>>? = null
        private var button: Pair<Map<String, String>, StringBuilder>? = null
        private var select: FieldBuilder? = null
        private var option: Pair<Map<String, String>, StringBuilder>? = null

        fun text(raw: String) {
            if (raw.isEmpty()) return
            val text = decode(raw)
            anchor?.second?.append(text)
            // A label wrapping a select is named by its own text, not by the option texts inside it.
            if (select == null) label?.second?.append(text)
            button?.second?.append(text)
            option?.second?.append(text)
        }

        fun open(
            name: String,
            attributes: Map<String, String>,
        ) {
            attributes["data-testid"]?.takeIf { it.isNotBlank() }?.let(testIds::add)
            if (REF_ATTRIBUTE in attributes) hasRefs = true
            when (name) {
                "form" -> {
                    closeForm()
                    form = FormBuilder(attributes)
                }

                "a" -> {
                    closeAnchor()
                    if (attributes["href"] != null) anchor = attributes to StringBuilder()
                }

                "label" -> {
                    closeLabel()
                    label = Triple(attributes["for"], StringBuilder(), mutableListOf())
                }

                "input" -> {
                    input(attributes)
                }

                "select" -> {
                    closeSelect()
                    select = FieldBuilder("select", "select", attributes).also(::addField)
                }

                "option" -> {
                    closeOption()
                    if (select != null) option = attributes to StringBuilder()
                }

                "textarea" -> {
                    addField(FieldBuilder("textarea", "textarea", attributes))
                }

                "button" -> {
                    closeButton()
                    button = attributes to StringBuilder()
                }

                "img" -> {
                    if ("alt" !in attributes) imagesWithoutAlt++
                }
            }
        }

        fun close(name: String) {
            when (name) {
                "form" -> closeForm()
                "a" -> closeAnchor()
                "label" -> closeLabel()
                "select" -> closeSelect()
                "option" -> closeOption()
                "button" -> closeButton()
            }
        }

        private fun input(attributes: Map<String, String>) {
            val type =
                attributes["type"]
                    ?.trim()
                    ?.lowercase()
                    .orEmpty()
                    .ifEmpty { "text" }
            when (type) {
                "hidden" -> {
                    // Not a field anyone fills in, but `_method` decides what submitting the form does.
                    val override = attributes["value"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
                    if (attributes["name"].equals(METHOD_OVERRIDE, ignoreCase = true) && override != null) {
                        form?.methodOverride = override
                    }
                }

                in BUTTON_INPUTS -> {
                    addButton(button("input", type, attributes, attributes["value"] ?: attributes["alt"] ?: ""))
                }

                else -> {
                    addField(FieldBuilder("input", type, attributes))
                }
            }
        }

        private fun addField(field: FieldBuilder) {
            label?.third?.add(field)
            form?.fields?.add(field) ?: looseFields.add(field)
        }

        private fun addButton(scanned: ScannedButton) {
            form?.buttons?.add(scanned) ?: looseButtons.add(scanned)
        }

        private fun button(
            tag: String,
            type: String,
            attributes: Map<String, String>,
            text: String,
        ) = ScannedButton(
            tag = tag,
            type = type,
            text = collapse(attributes["aria-label"]?.takeIf { it.isNotBlank() } ?: text),
            name = attributes["name"]?.takeIf { it.isNotBlank() },
            id = attributes["id"]?.takeIf { it.isNotBlank() },
            testId = attributes["data-testid"]?.takeIf { it.isNotBlank() },
            ref = attributes[REF_ATTRIBUTE]?.toIntOrNull(),
            formAction = attributes["formaction"]?.trim()?.takeIf { it.isNotEmpty() },
            formMethod = attributes["formmethod"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() },
        )

        private fun closeForm() {
            val current = form ?: return
            closeSelect()
            forms += current
            form = null
        }

        private fun closeAnchor() {
            val (attributes, text) = anchor ?: return
            val href = attributes["href"].orEmpty()
            val shown = attributes["aria-label"]?.takeIf { it.isNotBlank() } ?: text.toString()
            links += ScannedLink(href, collapse(shown), attributes[REF_ATTRIBUTE]?.toIntOrNull())
            anchor = null
        }

        private fun closeLabel() {
            val (forId, text, wrapped) = label ?: return
            val shown = collapse(text.toString())
            if (shown.isNotEmpty()) {
                forId?.takeIf { it.isNotBlank() }?.let { labelsFor.putIfAbsent(it, shown) }
                wrapped.forEach { if (it.label == null) it.label = shown }
            }
            label = null
        }

        private fun closeSelect() {
            closeOption()
            select = null
        }

        private fun closeOption() {
            val (attributes, text) = option ?: return
            val shown = collapse(attributes["label"] ?: text.toString())
            val value = attributes["value"] ?: shown
            if (shown.isNotEmpty() && value.isNotBlank()) select?.options?.add(shown)
            option = null
        }

        private fun closeButton() {
            val (attributes, text) = button ?: return
            val type =
                attributes["type"]
                    ?.trim()
                    ?.lowercase()
                    .orEmpty()
                    .ifEmpty { "submit" }
            addButton(button("button", type, attributes, text.toString()))
            button = null
        }

        fun result(): ScannedDocument {
            closeAnchor()
            closeLabel()
            closeButton()
            closeForm()
            closeSelect()
            return ScannedDocument(
                links = links.toList(),
                forms =
                    forms.map { builder ->
                        ScannedForm(
                            action = builder.attributes["action"]?.takeIf { it.isNotBlank() },
                            method =
                                builder.attributes["method"]
                                    ?.trim()
                                    ?.uppercase()
                                    ?.ifEmpty { null } ?: "GET",
                            id = builder.attributes["id"]?.takeIf { it.isNotBlank() },
                            testId = builder.attributes["data-testid"]?.takeIf { it.isNotBlank() },
                            fields = builder.fields.map { it.build(labelsFor) },
                            buttons = builder.buttons.toList(),
                            methodOverride = builder.methodOverride,
                        )
                    },
                looseFields = looseFields.map { it.build(labelsFor) },
                looseButtons = looseButtons.toList(),
                testIds = testIds.toList(),
                imagesWithoutAlt = imagesWithoutAlt,
                hasRefs = hasRefs,
            )
        }
    }
}
