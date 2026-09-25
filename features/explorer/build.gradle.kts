plugins {
    id("petek.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    `java-test-fixtures`
}

dependencies {
    api(project(":core:domain"))
    api(project(":features:browser"))
    api(project(":features:llm"))
    api(project(":features:campaign"))
    api(project(":features:evidence"))
    implementation(project(":core:sqlite"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlin.logging)
    testImplementation(testFixtures(project(":core:domain")))
    testImplementation(testFixtures(project(":features:browser")))
    testImplementation(testFixtures(project(":features:llm")))
    testImplementation(testFixtures(project(":features:evidence")))
    // One real-Chromium integration test explores the fake KadroHR (docs/PLAN.md Faza 6).
    testImplementation(project(":testing:fake-target"))
}
