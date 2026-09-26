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
}

dependencies {
    testImplementation(project(":app"))
    testImplementation(project(":testing:fake-target"))
    testImplementation(project(":core:sqlite"))
    testImplementation(project(":features:orchestration"))
    testImplementation(project(":features:reporting"))
    testImplementation(testFixtures(project(":core:domain")))
    testImplementation(testFixtures(project(":features:llm")))
    testImplementation(libs.konsist)
    testImplementation(libs.playwright)
}

// Architecture tests run with the normal build; browser end-to-end runs are opt-in (heavier).
// Konsist scans the repository from the working directory, so every test task runs from the root.
tasks.withType<Test>().configureEach {
    workingDir = rootProject.projectDir
}

tasks.register<Test>("e2eTest") {
    description = "End-to-end runs against the fake target with a real Chromium."
    group = "verification"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("e2e") }
    maxHeapSize = "3g"
    shouldRunAfter(tasks.test)
}

tasks.register<Test>("liveTest") {
    description = "Opt-in runs that call the real LLM provider (uses your Claude plan/quota)."
    group = "verification"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("live") }
    maxHeapSize = "3g"
}

// Kover instruments every Test task and its verification (part of `check`) runs them all; without this `build` would
// run the browser suite and, worse, the live suite that spends the real LLM quota. Both stay opt-in.
kover {
    currentProject {
        instrumentation {
            disabledForTestTasks.addAll("e2eTest", "liveTest")
        }
    }
}
