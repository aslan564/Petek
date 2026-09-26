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

// Root project: aggregated coverage over every module that applies petek.kotlin-jvm, and the formatting and copyright
// header of the build scripts that no module owns (the root and settings scripts, build-logic itself).
plugins {
    base
    id("org.jetbrains.kotlinx.kover")
    id("com.diffplug.spotless")
}

val libs = extensions.getByType<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs")

spotless {
    kotlinGradle {
        target("*.gradle.kts", "build-logic/*.gradle.kts", "build-logic/src/**/*.gradle.kts")
        ktlint(libs.findVersion("ktlint").get().requiredVersion)
        licenseHeader(PetekLicense.block, PetekLicense.GRADLE_DELIMITER)
    }
    kotlin {
        target("build-logic/src/**/*.kt")
        ktlint(libs.findVersion("ktlint").get().requiredVersion)
        licenseHeader(PetekLicense.block, PetekLicense.KOTLIN_DELIMITER)
    }
}
