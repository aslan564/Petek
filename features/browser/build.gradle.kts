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

// Kover instruments every Test task and its verification (part of `check`) runs them all; without this `build` would
// open thirty Chromium contexts. The proof runs with `./gradlew e2eTest` (root) and in CI's e2e job.
kover {
    currentProject {
        instrumentation {
            disabledForTestTasks.add("isolationTest")
        }
    }
}
