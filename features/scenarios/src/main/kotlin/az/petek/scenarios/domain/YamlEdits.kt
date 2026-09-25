/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.scenarios.domain

/**
 * One exact text replacement in a scenario YAML: [find] must occur exactly once in the text it is applied to.
 * Proposals are expressed as edits rather than as a rewritten file, so everything the proposal does not touch stays
 * byte-for-byte the same and the owner's diff shows only the intended change.
 */
data class YamlEdit(
    val find: String,
    val replace: String,
)

/** Applies [YamlEdit]s in order, each to the result of the previous one. Pure. */
object YamlEdits {
    const val MAX_EDITS: Int = 20

    sealed interface Result {
        data class Applied(
            val yaml: String,
        ) : Result

        data class Failed(
            val reason: String,
        ) : Result
    }

    fun apply(
        yaml: String,
        edits: List<YamlEdit>,
    ): Result {
        if (edits.isEmpty()) return Result.Failed("the change has no edits")
        if (edits.size > MAX_EDITS) return Result.Failed("the change has ${edits.size} edits; at most $MAX_EDITS are allowed")
        val crlf = yaml.contains("\r\n")
        var current = yaml
        edits.forEachIndexed { index, edit ->
            val number = index + 1
            if (edit.find.isEmpty()) return Result.Failed("edit $number has an empty 'find'")
            val find = if (crlf) toCrlf(edit.find) else edit.find
            val replace = if (crlf) toCrlf(edit.replace) else edit.replace
            if (find == replace) return Result.Failed("edit $number replaces a text with itself")
            val first = current.indexOf(find)
            if (first < 0) return Result.Failed("edit $number: the text to find does not occur in the scenario: ${excerpt(edit.find)}")
            if (current.indexOf(find, first + 1) >= 0) {
                return Result.Failed("edit $number: the text to find occurs more than once, so it is ambiguous: ${excerpt(edit.find)}")
            }
            current = current.substring(0, first) + replace + current.substring(first + find.length)
        }
        return if (current == yaml) Result.Failed("the edits do not change the scenario") else Result.Applied(current)
    }

    /** A file written with CRLF line breaks gets the model's LF-only edits in CRLF form, so they still match. */
    private fun toCrlf(text: String): String = text.replace("\r\n", "\n").replace("\n", "\r\n")

    private fun excerpt(text: String): String {
        val oneLine = text.replace("\r", "").replace("\n", "⏎")
        return "'" + (if (oneLine.length > EXCERPT) oneLine.take(EXCERPT) + "…" else oneLine) + "'"
    }

    private const val EXCERPT = 80
}
