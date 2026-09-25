/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

/**
 * The copyright and licence header Spotless puts on (and checks in) every source file of Pətək. `spotlessCheck` is part
 * of `build`, so a file without the header fails the build; `spotlessApply` adds it. Change the wording here only.
 */
object PetekLicense {
    private val lines =
        listOf(
            "Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek",
            "Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.",
            "",
            "Licensed under the Business Source License 1.1 (the \"License\"); you may not use this file except in",
            "compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;",
            "Change License: Apache License, Version 2.0. The Licensed Work is provided \"AS IS\", without warranty.",
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
