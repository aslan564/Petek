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
    application
}

dependencies {
    implementation(project(":core:sqlite"))
    implementation(project(":features:campaign"))
    implementation(project(":features:identity"))
    implementation(project(":features:evidence"))
    implementation(project(":features:mail"))
    implementation(project(":features:oracle"))
    implementation(project(":features:browser"))
    implementation(project(":features:llm"))
    implementation(project(":features:agent"))
    implementation(project(":features:verification"))
    implementation(project(":features:orchestration"))
    implementation(project(":features:reporting"))
    implementation(project(":features:capacity"))
    implementation(project(":features:dashboard"))
    implementation(project(":features:explorer"))
    implementation(project(":features:scenarios"))
    // The start menu's demo runs the local fake KadroHR in-process; it is a test stand-in, never a production target.
    implementation(project(":testing:fake-target"))
    implementation(libs.clikt)
    // The explorer's stored answers (a small JSON file next to the evidence).
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mordant)
    implementation(libs.kotlin.logging)
    // Compile access: the MDC helper and the programmatic log setup (log directory from the configuration).
    implementation(libs.logback.classic)
    testImplementation(testFixtures(project(":core:domain")))
    testImplementation(testFixtures(project(":features:browser")))
    testImplementation(testFixtures(project(":features:llm")))
    testImplementation(testFixtures(project(":features:evidence")))
    testImplementation(testFixtures(project(":features:identity")))
    testImplementation(testFixtures(project(":features:explorer")))
    testImplementation(testFixtures(project(":features:scenarios")))
    testImplementation(testFixtures(project(":features:oracle")))
    testImplementation(project(":testing:fake-target"))
    // The panel end-to-end test drives the real page in Chromium and takes screenshots (tag "e2e").
    testImplementation(libs.playwright)
}

application {
    mainClass.set("az.petek.app.MainKt")
    applicationName = "petek"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// The sidecar distribution (R15): `petek-<version>.zip` with bin/petek, every jar, the licence, the configuration
// template and the example scenarios. It runs from any directory next to the site under test; the site's own build
// never depends on Pətək. Published by .github/workflows/release.yml on a v<version> tag.
distributions {
    main {
        distributionBaseName.set("petek")
        contents {
            from(rootProject.file("LICENSE"))
            from(rootProject.file("NOTICE"))
            from(rootProject.file("README.md"))
            from(rootProject.file("README.az.md"))
            from(rootProject.file(".env.example"))
            into("scenarios") { from(rootProject.file("scenarios")) }
            into("docs") { from(rootProject.file("docs/TARGET_CONTRACT.md")) }
        }
    }
}

// Every way of launching main (the run task, IntelliJ's run icon next to main(), run configurations) gets the JVM
// option that sqlite-jdbc, Playwright and JNA need on JDK 25; IntelliJ's icon creates its own JavaExec task.
tasks.withType<JavaExec>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // IntelliJ's run icon creates its own JavaExec task whose working directory would be app/; Pətək reads .env,
    // scenarios/ and writes evidence/ relative to the project root.
    workingDir = rootProject.projectDir
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}

// The panel end to end in real Chromium against the in-process fake KadroHR, with screenshots of every screen in
// build/panel-screenshots/ (tag "e2e", kept out of the fast build): ./gradlew :app:e2eTest
tasks.register<Test>("e2eTest") {
    description = "The web panel end to end against the fake target in real Chromium, with screenshots."
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

// Kover instruments every Test task and its verification (part of `check`) runs them all; without this `build` would
// start Chromium for the panel end-to-end test.
kover {
    currentProject {
        instrumentation {
            disabledForTestTasks.add("e2eTest")
        }
    }
}
