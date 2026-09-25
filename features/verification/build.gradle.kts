plugins {
    id("petek.kotlin-jvm")
}

dependencies {
    api(project(":core:domain"))
    api(project(":features:campaign"))
    api(project(":features:browser"))
    api(project(":features:oracle"))
    api(project(":features:evidence"))
    implementation(libs.kotlin.logging)
    testImplementation(testFixtures(project(":core:domain")))
    testImplementation(testFixtures(project(":features:browser")))
    testImplementation(testFixtures(project(":features:oracle")))
    testImplementation(testFixtures(project(":features:evidence")))
}
