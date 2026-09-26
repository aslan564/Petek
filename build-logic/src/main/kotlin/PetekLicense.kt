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

/**
 * The copyright and licence header Spotless puts on (and checks in) every source file of Pətək. `spotlessCheck` is part
 * of `build`, so a file without the header fails the build; `spotlessApply` adds it. Change the wording here only.
 */
object PetekLicense {
    private val lines =
        listOf(
            "Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek",
            "Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.",
            "",
            "Licensed under the Apache License, Version 2.0 (the \"License\"); you may not use this file except in",
            "compliance with the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0",
            "Unless required by applicable law or agreed to in writing, software distributed under the License is",
            "distributed on an \"AS IS\" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.",
            "See the License for the specific language governing permissions and limitations under the License.",
        )

    /** Block comment (slash-star … star-slash) for Kotlin, Gradle scripts, JavaScript and CSS; a blank line follows it. */
    val block: String = (listOf("/*") + lines.map { if (it.isEmpty()) " *" else " * $it" } + listOf(" */", "", "")).joinToString("\n")

    /** `<!-- … -->` block for HTML. */
    val html: String = (listOf("<!--") + lines.map { "  $it".trimEnd() } + listOf("-->", "", "")).joinToString("\n")

    // Spotless anchors every delimiter to the start of a line itself; the header's own lines never match one.

    /** Kotlin: before the first package, file annotation, import, KDoc or top-level declaration (package-less files). */
    const val KOTLIN_DELIMITER = "(package |@file|import |/\\*\\*|object |class |internal |private |fun )"

    /** Gradle scripts: before the first statement or line comment. */
    const val GRADLE_DELIMITER =
        "(plugins|pluginManagement|dependencyResolutionManagement|rootProject|include|import |val |//|@file|" +
            "enableFeaturePreview|tasks|dependencies|kotlin|java|application|spotless|repositories|group|version)"

    /** JavaScript and CSS: before the first line comment, the first block comment with text on its first line, or code. */
    const val WEB_DELIMITER = "(//|/\\* \\S|[A-Za-z(:{'@.#\\[])"

    const val HTML_DELIMITER = "<!doctype"
}
