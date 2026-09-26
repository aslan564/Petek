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
    inputs.file(rootDir.resolve("docs/examples/company-portal.yaml")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootDir.resolve("scenarios/contract-demo.yaml")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootDir.resolve("docs/PLAN.md")).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("petek.repoRoot", rootDir.absolutePath)
}
