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
    api(project(":core:domain"))
    api(project(":features:campaign"))
    api(project(":features:identity"))
    api(project(":features:evidence"))
    api(project(":features:browser"))
    api(project(":features:oracle"))
    api(project(":features:agent"))
    api(project(":features:verification"))
    implementation(libs.mordant)
    implementation(libs.kotlin.logging)
    testImplementation(testFixtures(project(":core:domain")))
    testImplementation(testFixtures(project(":features:browser")))
    testImplementation(testFixtures(project(":features:llm")))
    testImplementation(testFixtures(project(":features:mail")))
    testImplementation(testFixtures(project(":features:oracle")))
    testImplementation(testFixtures(project(":features:evidence")))
    testImplementation(testFixtures(project(":features:identity")))
}

// TesterIsolationAtScaleTest proves isolation with 100 and 1 000 testers on every build; -Dpetek.isolation.testers=5000 adds more.
tasks.withType<Test>().configureEach {
    System.getProperty("petek.isolation.testers")?.let { systemProperty("petek.isolation.testers", it) }
}
