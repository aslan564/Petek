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
