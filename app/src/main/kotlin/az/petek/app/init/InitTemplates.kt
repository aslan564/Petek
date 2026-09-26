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

package az.petek.app.init

import java.net.URI

/**
 * The texts `petek init` writes, read from the resources next to this class: the configuration template (the
 * repository's `.env.example`, copied in by the build), the project profile, the skill pack and the per-agent fragment.
 */
class InitTemplates internal constructor(
    private val read: (String) -> String,
) {
    /** `.env` from `.env.example`; with [target] the `PETEK_TARGET` line is filled in. */
    fun env(target: URI?): String {
        val template = read("env.example")
        if (target == null) return template
        return template.lines().joinToString("\n") { line ->
            if (line.startsWith("PETEK_TARGET=")) "PETEK_TARGET=$target" else line
        }
    }

    fun profile(target: URI?): String = read("petek.yaml").replace(TARGET_PLACEHOLDER, target?.toString() ?: "https://staging.example.com")

    fun skill(): String = read("SKILL.md")

    /** The fragment for [ai]'s instruction file; Cursor's rules file needs its front matter. */
    fun fragment(ai: HostAi): String {
        val body = read("fragment.md").trimEnd()
        return if (ai == HostAi.CURSOR) "$CURSOR_FRONT_MATTER\n$body" else body
    }

    companion object {
        private const val TARGET_PLACEHOLDER = "{target}"
        private val CURSOR_FRONT_MATTER =
            listOf("---", "description: Pətək, the multi-agent AI test platform running next to this project", "alwaysApply: false", "---")
                .joinToString("\n")

        fun bundled(): InitTemplates =
            InitTemplates { name ->
                requireNotNull(InitTemplates::class.java.getResource(name)) { "missing init template $name" }.readText()
            }
    }
}
