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
