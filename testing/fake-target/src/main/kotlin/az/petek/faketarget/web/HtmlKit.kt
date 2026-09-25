/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.faketarget.web

import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.INPUT
import kotlinx.html.InputType
import kotlinx.html.Tag
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.select
import kotlinx.html.textArea

/*
 * Small builders so every form control gets the same accessible shape: a real <label for=…>, an id equal to its
 * data-testid, and errors in role=alert paragraphs that assistive tech (and Pətək's snapshot) can read.
 */

internal fun Tag.testId(id: String) {
    attributes["data-testid"] = id
}

internal fun Tag.dataId(id: String) {
    attributes["data-id"] = id
}

internal fun FlowContent.inputField(
    label: String,
    id: String,
    name: String,
    type: InputType = InputType.text,
    value: String? = null,
    autocomplete: String? = null,
    configure: INPUT.() -> Unit = {},
) {
    div("field") {
        label {
            htmlFor = id
            +label
        }
        input(type = type, name = name) {
            this.id = id
            testId(id)
            if (value != null && type != InputType.password) this.value = value
            if (autocomplete != null) attributes["autocomplete"] = autocomplete
            configure()
        }
    }
}

internal fun FlowContent.textAreaField(
    label: String,
    id: String,
    name: String,
    value: String = "",
) {
    div("field") {
        label {
            htmlFor = id
            +label
        }
        textArea(rows = "4") {
            this.id = id
            this.name = name
            testId(id)
            +value
        }
    }
}

/** A select whose options are (value, label); [placeholder] adds an empty first option. */
internal fun FlowContent.selectField(
    label: String,
    id: String,
    name: String,
    options: List<Pair<String, String>>,
    selected: String? = null,
    placeholder: String? = null,
) {
    div("field") {
        label {
            htmlFor = id
            +label
        }
        select {
            this.id = id
            this.name = name
            testId(id)
            if (placeholder != null) {
                option {
                    value = ""
                    +placeholder
                }
            }
            options.forEach { (optionValue, optionLabel) ->
                option {
                    value = optionValue
                    if (optionValue == selected) this.selected = true
                    +optionLabel
                }
            }
        }
    }
}

internal fun FlowContent.submitButton(
    id: String,
    text: String,
    style: String? = null,
) {
    button(type = ButtonType.submit, classes = style) {
        testId(id)
        +text
    }
}

internal fun FlowContent.alert(
    id: String,
    message: String?,
) {
    if (message == null) return
    p("error") {
        attributes["role"] = "alert"
        testId(id)
        +message
    }
}

internal fun FlowContent.notice(
    id: String,
    message: String?,
) {
    if (message == null) return
    p("info") {
        attributes["role"] = "status"
        testId(id)
        +message
    }
}
