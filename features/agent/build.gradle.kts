plugins {
    id("petek.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(project(":core:domain"))
    api(project(":features:campaign"))
    api(project(":features:identity"))
    api(project(":features:browser"))
    api(project(":features:llm"))
    api(project(":features:mail"))
    api(project(":features:oracle"))
    api(project(":features:evidence"))
    implementation(libs.kotlin.logging)
    testImplementation(testFixtures(project(":core:domain")))
    testImplementation(testFixtures(project(":features:browser")))
    testImplementation(testFixtures(project(":features:llm")))
    testImplementation(testFixtures(project(":features:mail")))
    testImplementation(testFixtures(project(":features:oracle")))
    testImplementation(testFixtures(project(":features:evidence")))
    testImplementation(testFixtures(project(":features:identity")))
}
