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
    // The start menu's demo runs the local fake KadroHR in-process; it is a test stand-in, never a production target.
    implementation(project(":testing:fake-target"))
    implementation(libs.clikt)
    implementation(libs.mordant)
    implementation(libs.kotlin.logging)
    // Compile access: the MDC helper and the programmatic log setup (log directory from the configuration).
    implementation(libs.logback.classic)
    testImplementation(testFixtures(project(":core:domain")))
    testImplementation(testFixtures(project(":features:browser")))
    testImplementation(testFixtures(project(":features:llm")))
    testImplementation(testFixtures(project(":features:evidence")))
    testImplementation(testFixtures(project(":features:identity")))
    testImplementation(project(":testing:fake-target"))
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
    // The start menu reads the user's choice from the console (also in IntelliJ's run window).
    standardInput = System.`in`
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}
