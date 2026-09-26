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

// What every distribution carries besides the jars: the licence, the user documentation, the configuration template,
// the example scenarios and the target contract.
fun CopySpec.distributionDocuments() {
    from(rootProject.file("LICENSE"))
    from(rootProject.file("NOTICE"))
    from(rootProject.file("README.md"))
    from(rootProject.file("README.az.md"))
    from(rootProject.file(".env.example"))
    into("scenarios") { from(rootProject.file("scenarios")) }
    into("docs") { from(rootProject.file("docs/TARGET_CONTRACT.md")) }
}

// The generic sidecar distribution (R15): `petek-<version>-any-jdk25.zip` with bin/petek, every jar and the documents;
// it runs on any platform that has JDK 25 on PATH. The platform bundles below need no JDK. Both run from any directory
// next to the site under test; the site's own build never depends on Pətək. Published by .github/workflows/release.yml.
distributions {
    main {
        distributionBaseName.set("petek")
        distributionClassifier.set("any-jdk25")
        contents { distributionDocuments() }
    }
}

// ---- Platform bundles (R15, Faza 12a): `petek-<version>-<platform>.tar.gz` (zip on Windows) ------------------------
// A bundle is bin/petek (a small shell launcher, petek.cmd on Windows), lib/ with the jars, runtime/ with a jlink image
// of the JDK modules the app needs, and the documents. Two things make it small: the runtime holds only the modules
// jdeps found plus locale, charset and EC crypto data, and Playwright's driver-bundle jar (Node.js for five platforms,
// about 200 MB) is repacked with the one platform the bundle is for. Platforms and Playwright's directory for each:
val bundlePlatforms =
    mapOf(
        "linux-x64" to "linux",
        "linux-arm64" to "linux-arm64",
        "mac-x64" to "mac",
        "mac-arm64" to "mac-arm64",
        "win-x64" to "win32_x64",
    )

fun hostPlatform(): String {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")
    return when {
        os.contains("win") -> "win-x64"
        os.contains("mac") -> if (arm) "mac-arm64" else "mac-x64"
        else -> if (arm) "linux-arm64" else "linux-x64"
    }
}

// -Ppetek.platform=<platform> chooses the Playwright driver and the archive name; the runtime is always built from the
// JDK that runs the build (jlink cannot cross-build without that platform's jmods), so a bundle is built on its own
// platform, as the release workflow's matrix does. The default is the host.
val bundlePlatform: String =
    providers
        .gradleProperty("petek.platform")
        .orElse(hostPlatform())
        .get()
        .also {
            require(it in bundlePlatforms) { "petek.platform must be one of ${bundlePlatforms.keys}, not '$it'" }
        }
val bundleDriverDirectory = bundlePlatforms.getValue(bundlePlatform)
val bundleName = "petek-${project.version}"
val bundleRoot = "$bundleName-$bundlePlatform"

val runtimeJars = configurations.runtimeClasspath.map { it.filter { jar -> !jar.name.startsWith("driver-bundle-") } }
val driverBundleJar = configurations.runtimeClasspath.map { it.files.single { jar -> jar.name.startsWith("driver-bundle-") } }

val slimDriverBundle =
    tasks.register<Jar>("slimDriverBundle") {
        description = "Playwright's driver-bundle jar with only the $bundlePlatform driver."
        group = "distribution"
        archiveBaseName.set("driver-bundle")
        archiveVersion.set(libs.versions.playwright)
        archiveClassifier.set(bundlePlatform)
        destinationDirectory.set(layout.buildDirectory.dir("bundle/lib"))
        // A local copy: the spec is stored in the configuration cache and must not reference the build script.
        val keep = bundleDriverDirectory
        from(zipTree(driverBundleJar)) {
            exclude { element ->
                val path = element.relativePath.segments
                path.size >= 2 && path[0] == "driver" && path[1] != keep
            }
        }
    }

// The modules jdeps lists for the app's jars (sqlite-jdbc, Playwright, Ktor, logback, the SDK), plus what jdeps cannot
// see: every locale (dates and names in Azerbaijani), the extra charsets, EC certificates for TLS, and zip file systems.
val runtimeModules =
    listOf(
        "java.base",
        "java.desktop",
        "java.instrument",
        "java.naming",
        "java.net.http",
        "java.sql",
        "jdk.management",
        "jdk.unsupported",
        "jdk.localedata",
        "jdk.crypto.ec",
        "jdk.charsets",
        "jdk.zipfs",
    )

val toolchainHome =
    javaToolchains
        .launcherFor {
            languageVersion.set(
                JavaLanguageVersion.of(
                    libs.versions.jdk
                        .get()
                        .toInt(),
                ),
            )
        }.map { it.metadata.installationPath.asFile }

val jlinkRuntime =
    tasks.register<Exec>("jlinkRuntime") {
        description = "A jlink image of the JDK modules Pətək needs, for the $bundlePlatform bundle."
        group = "distribution"
        val image = layout.buildDirectory.dir("bundle/runtime")
        val jdk = toolchainHome.get()
        // A JDK built as a linkable run-time image (JDK 24+, e.g. Temurin for Linux arm64) ships no jmods/ directory;
        // jlink then links from the JDK's own lib/modules, so the module path is given only when the directory exists.
        val jmods = jdk.resolve("jmods").takeIf { it.isDirectory }
        inputs.property("modules", runtimeModules)
        inputs.file(jdk.resolve("lib/modules"))
        jmods?.let { inputs.dir(it) }
        outputs.dir(image)
        executable = jdk.resolve("bin/jlink").path
        // jlink refuses an existing output directory.
        doFirst { image.get().asFile.deleteRecursively() }
        jmods?.let { args("--module-path", it.path) }
        args(
            "--add-modules",
            runtimeModules.joinToString(","),
            "--strip-debug",
            "--no-header-files",
            "--no-man-pages",
            "--compress",
            "zip-6",
            "--output",
            image.get().asFile.path,
        )
    }

// The layout every bundle archive shares; the launcher scripts stay executable.
fun CopySpec.bundleLayout() {
    into(bundleRoot)
    into("bin") {
        from(file("src/bundle/bin"))
        if (bundlePlatform == "win-x64") exclude("petek") else exclude("petek.cmd")
        filePermissions { unix("rwxr-xr-x") }
    }
    into("lib") {
        from(tasks.jar)
        from(runtimeJars)
        from(slimDriverBundle)
    }
    // Gradle's archives do not keep the image's permission bits; the runtime's programs get them back explicitly.
    val runtimePrograms = listOf("bin/**", "lib/jspawnhelper", "lib/jexec")
    into("runtime") {
        from(jlinkRuntime) { exclude(runtimePrograms) }
        from(jlinkRuntime) {
            include(runtimePrograms)
            filePermissions { unix("rwxr-xr-x") }
        }
    }
    distributionDocuments()
}

val bundleTar =
    tasks.register<Tar>("bundleTar") {
        description = "The $bundlePlatform bundle as petek-<version>-$bundlePlatform.tar.gz (a JDK is not needed)."
        group = "distribution"
        compression = Compression.GZIP
        archiveFileName.set("$bundleRoot.tar.gz")
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
        bundleLayout()
    }

val bundleZip =
    tasks.register<Zip>("bundleZip") {
        description = "The $bundlePlatform bundle as petek-<version>-$bundlePlatform.zip (a JDK is not needed)."
        group = "distribution"
        archiveFileName.set("$bundleRoot.zip")
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
        bundleLayout()
    }

// `./gradlew :app:bundle` builds the archive for the host (tar.gz; zip on Windows, which has no tar permissions to
// keep); `-Ppetek.platform=` names another platform's Playwright driver, see above.
tasks.register("bundle") {
    description = "The platform bundle for $bundlePlatform: build/distributions/$bundleRoot.tar.gz or .zip."
    group = "distribution"
    dependsOn(if (bundlePlatform == "win-x64") bundleZip else bundleTar)
}

// `petek init` writes the project's .env from the repository's own template, so the two never drift apart.
tasks.processResources {
    from(rootProject.file(".env.example")) {
        into("az/petek/app/init")
        rename { "env.example" }
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
