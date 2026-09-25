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
 * Lines of campaign-file elements that the domain model does not carry itself, so validation issues about settings,
 * assertions or id sources can still point at the YAML.
 *
 * Keys are YAML paths as written in the file: map keys joined with `.`, list items as `[index]` (0-based), e.g.
 * `campaign.testers`, `campaign.names[2]`, `target_profile.id_sources.ticket_created`, `steps[3].assert[1]`;
 * [ROOT] is the file itself.
 * A campaign built in code has [NONE] and its issues fall back to [ScenarioStep.line] or no line at all.
 */
data class SourceLines(
    val byPath: Map<String, Int>,
) {
    /** Line of [path], or of its closest recorded ancestor (a defaulted key points at its parent section). */
    fun lineOf(path: String): Int? {
        var current: String? = path
        while (current != null) {
            byPath[current]?.let { return it }
            current = parentOf(current)
        }
        return null
    }

    companion object {
        val NONE: SourceLines = SourceLines(emptyMap())

        /** Path of the whole file (the root node). */
        const val ROOT: String = ""

        /** `steps[3].assert[1]` -> `steps[3]` -> `steps` -> [ROOT] -> null. */
        fun parentOf(path: String): String? {
            if (path == ROOT) return null
            if (path.endsWith("]")) {
                val open = path.lastIndexOf('[')
                if (open > 0) return path.substring(0, open)
            }
            val dot = path.lastIndexOf('.')
            return if (dot > 0) path.substring(0, dot) else ROOT
        }
    }
}
