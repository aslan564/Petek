/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
