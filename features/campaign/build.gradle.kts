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
    // Tests load the real campaign file and compare the copy in docs/PLAN.md with it, so both are test inputs and
    // the repository location is passed to the JVM.
    inputs.file(rootDir.resolve("scenarios/kadrohr.yaml")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootDir.resolve("docs/PLAN.md")).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("petek.repoRoot", rootDir.absolutePath)
}
