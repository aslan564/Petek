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
    // Tests load the real campaign file, so it is a test input and its location is passed to the JVM.
    inputs.file(rootDir.resolve("scenarios/kadrohr.yaml")).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("petek.repoRoot", rootDir.absolutePath)
}
