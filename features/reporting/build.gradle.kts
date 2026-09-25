plugins {
    id("petek.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(project(":core:domain"))
    api(project(":features:evidence"))
    implementation(libs.kotlinx.html)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(testFixtures(project(":core:domain")))
    testImplementation(testFixtures(project(":features:evidence")))
}
