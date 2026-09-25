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
    implementation(libs.playwright)
    implementation(libs.kotlin.logging)
    testImplementation(testFixtures(project(":core:domain")))
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.ktor.server.sse)
}

// The isolation proof with real Chromium (BrowserIsolationAtScaleTest, tagged e2e) has its own task; it opens
// -Dpetek.isolation.sessions=N contexts (default 30). CI runs it in the e2e job.
tasks.register<Test>("isolationTest") {
    description = "Tester isolation with N real Chromium contexts (petek.isolation.sessions, default 30)."
    group = "verification"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("e2e") }
    maxHeapSize = "2g"
    System.getProperty("petek.isolation.sessions")?.let { systemProperty("petek.isolation.sessions", it) }
    shouldRunAfter(tasks.test)
}
