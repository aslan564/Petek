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

// Shared conventions for every Kotlin/JVM module: toolchain, strict compiler, tests, formatting, coverage.
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.diffplug.spotless")
    id("org.jetbrains.kotlinx.kover")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun lib(alias: String) = libs.findLibrary(alias).get()

kotlin {
    jvmToolchain(
        libs
            .findVersion("jdk")
            .get()
            .requiredVersion
            .toInt(),
    )
    compilerOptions {
        allWarningsAsErrors.set(true)
        progressiveMode.set(true)
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xconsistent-data-class-copy-visibility")
    }
}

dependencies {
    "implementation"(lib("kotlinx-coroutines-core"))
    "testImplementation"(platform(lib("junit-bom")))
    "testImplementation"(lib("junit-jupiter"))
    "testImplementation"(lib("kotest-assertions-core"))
    "testImplementation"(lib("kotlinx-coroutines-test"))
    "testRuntimeOnly"(lib("junit-platform-launcher"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "1g"
    // sqlite-jdbc and Playwright load native code; JDK 25 warns unless native access is granted explicitly.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
        // On CI the test reports are hard to reach, so the tests' own output (the panel's logs, the harness's
        // warnings) goes to the job log; locally the report files are next to the build.
        showStandardStreams = providers.environmentVariable("CI").isPresent
    }
}

// The default `test` task stays fast: browser end-to-end and real-LLM tests have their own tasks.
tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("e2e", "live")
    }
}

// Formatting and the copyright header (PetekLicense) on every source file; `spotlessCheck` runs with `build`.
spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint(libs.findVersion("ktlint").get().requiredVersion)
        licenseHeader(PetekLicense.block, PetekLicense.KOTLIN_DELIMITER)
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(libs.findVersion("ktlint").get().requiredVersion)
        licenseHeader(PetekLicense.block, PetekLicense.GRADLE_DELIMITER)
    }
    format("webScripts") {
        target("src/main/resources/**/*.js", "src/main/resources/**/*.css")
        licenseHeader(PetekLicense.block, PetekLicense.WEB_DELIMITER)
    }
    format("webPages") {
        target("src/main/resources/**/*.html")
        licenseHeader(PetekLicense.html, PetekLicense.HTML_DELIMITER)
    }
}
