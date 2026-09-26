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
    api(project(":features:evidence"))
    api(project(":features:identity"))
    api(project(":features:orchestration"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.sse)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlin.logging)
    testImplementation(testFixtures(project(":core:domain")))
    testImplementation(testFixtures(project(":features:evidence")))
    testImplementation(testFixtures(project(":features:identity")))
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.playwright)
    testRuntimeOnly(libs.logback.classic)
}

// The whole panel with simulated data, for looking at it by hand: ./gradlew :features:dashboard:panelDemo
tasks.register<JavaExec>("panelDemo") {
    description = "Serves the Pətək panel with a simulated backend on http://127.0.0.1:7070 (args: --port P --agents N --no-run)."
    group = "application"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("az.petek.dashboard.demo.PanelDemoKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    standardInput = System.`in`
}
