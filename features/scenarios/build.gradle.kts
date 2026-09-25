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
    `java-test-fixtures`
}

dependencies {
    api(project(":core:domain"))
    api(project(":features:campaign"))
    api(project(":features:evidence"))
    api(project(":features:llm"))
    implementation(project(":core:sqlite"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlin.logging)
    testFixturesImplementation(libs.kotlinx.coroutines.core)
    testImplementation(testFixtures(project(":core:domain")))
    testImplementation(testFixtures(project(":features:evidence")))
    testImplementation(testFixtures(project(":features:llm")))
}
