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
    description = "Opt-in runs that call the real LLM provider (uses your own AI plan or quota)."
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
