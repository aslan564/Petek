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

package az.petek.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.architecture.KoArchitectureCreator.assertArchitecture
import com.lemonappdev.konsist.api.architecture.Layer
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.ext.list.withPackage
import com.lemonappdev.konsist.api.provider.KoNameProvider
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

/**
 * The architecture rules of AGENTS.md, checked on every build (ADR-0001): feature-based clean architecture with three
 * layers per feature, dependencies through ports, `app` as the only composition root. Production sources of every
 * module are in scope; the e2e task runs from the repository root so Konsist sees all of them.
 */
class ArchitectureTest {
    private val scope: KoScope = Konsist.scopeFromProduction()

    private val domainFiles: List<KoFileDeclaration> = scope.files.withPackage("az.petek..domain..")
    private val applicationFiles: List<KoFileDeclaration> = scope.files.withPackage("az.petek..application..")
    private val infrastructureFiles: List<KoFileDeclaration> = scope.files.withPackage("az.petek..infrastructure..")

    @Test
    fun `layers depend inwards only, domain on nothing, application on domain, infrastructure on both`() {
        scope.assertArchitecture {
            val domain = Layer("Domain", "az.petek..domain..")
            val application = Layer("Application", "az.petek..application..")
            val infrastructure = Layer("Infrastructure", "az.petek..infrastructure..")
            domain.dependsOnNothing()
            application.dependsOn(domain)
            infrastructure.dependsOn(domain, application)
        }
    }

    @Test
    fun `the domain layer is pure Kotlin without framework imports`() {
        domainFiles.assertFalse(testName = "domain imports a framework") { file ->
            file.hasImport { import -> FRAMEWORK_PREFIXES.any { import.name.startsWith(it) } }
        }
    }

    @Test
    fun `the domain layer never imports application or infrastructure code`() {
        domainFiles.assertFalse { file ->
            file.hasImport { it.name.contains(".application.") || it.name.contains(".infrastructure.") }
        }
    }

    @Test
    fun `the application layer never imports infrastructure`() {
        applicationFiles.assertFalse { file -> file.hasImport { it.name.contains(".infrastructure.") } }
    }

    @Test
    fun `infrastructure imports only its own feature's infrastructure, other features' through ports`() {
        infrastructureFiles.assertTrue { file ->
            val own = featureOf(file.packagee?.name.orEmpty())
            file.imports.all { import ->
                INFRASTRUCTURE_IMPORT
                    .find(import.name)
                    ?.groupValues
                    ?.get(1)
                    ?.let { it == own } ?: true
            }
        }
    }

    @Test
    fun `only the app is the composition root, nothing else imports it`() {
        scope.files
            .filterNot {
                it.packagee
                    ?.name
                    .orEmpty()
                    .startsWith("az.petek.app")
            }.assertFalse { file -> file.hasImport { it.name.startsWith("az.petek.app.") } }
    }

    @Test
    fun `the open core never imports a paid edition module, whether one exists or not`() {
        scope.files.assertFalse(testName = "open core imports a paid module (ADR-0011)") { file ->
            file.hasImport { import -> PAID_EDITION_PREFIXES.any { import.name.startsWith(it) } }
        }
    }

    @Test
    fun `the core knows no HR concept, whatever site is tested`() {
        val core = scope.files.withPackage("az.petek.core..")
        core.assertFalse(testName = "core names an HR concept") { file ->
            file.declarations(includeNested = true).any { declaration ->
                val name = (declaration as? KoNameProvider)?.name.orEmpty()
                HR_CONCEPT.containsMatchIn(name)
            }
        }
    }

    @Test
    fun `every feature keeps its three layers under its own package root`() {
        (domainFiles + applicationFiles + infrastructureFiles).assertTrue { file ->
            LAYER_PACKAGE.matches(file.packagee?.name.orEmpty())
        }
    }

    private fun featureOf(packageName: String): String = packageName.removePrefix("az.petek.").substringBefore('.')

    private companion object {
        /** Words of one kind of site (KadroHR); a declaration of the core named after one would tie Pətək to it. */
        val HR_CONCEPT = Regex("(?i)department|announcement|ticket|leave|payroll|salary|vacation|kadro")

        /** Paid implementations live in a separate repository behind the core's ports (ADR-0011). */
        val PAID_EDITION_PREFIXES = listOf("az.petek.premium", "az.petek.enterprise", "az.petek.hosted", "az.petek.cloud")

        val FRAMEWORK_PREFIXES =
            listOf(
                "io.ktor",
                "com.microsoft.playwright",
                "org.jetbrains.exposed",
                "com.anthropic",
                "com.github.ajalt",
                "java.sql",
                "org.slf4j",
                "ch.qos.logback",
            )
        val INFRASTRUCTURE_IMPORT = Regex("""^az\.petek\.([a-z]+)\.infrastructure\.""")
        val LAYER_PACKAGE = Regex("""^az\.petek\.[a-z]+\.(domain|application|infrastructure)(\..+)?$""")
    }
}
