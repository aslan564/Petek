/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

plugins {
    id("petek.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(project(":core:domain"))
    implementation(libs.kaml)
    testImplementation(testFixtures(project(":core:domain")))
}

tasks.named<Test>("test") {
    // Tests load the campaign files and compare the copy in docs/PLAN.md with the contract demo, so they are test
    // inputs and the repository location is passed to the JVM.
    inputs.file(rootDir.resolve("scenarios/kadrohr.yaml")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootDir.resolve("scenarios/contract-demo.yaml")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootDir.resolve("docs/PLAN.md")).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("petek.repoRoot", rootDir.absolutePath)
}
