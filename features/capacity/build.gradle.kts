plugins {
    id("petek.kotlin-jvm")
}

dependencies {
    api(project(":core:domain"))
    api(project(":features:browser"))
    implementation(libs.kotlin.logging)
    testImplementation(testFixtures(project(":core:domain")))
    testImplementation(testFixtures(project(":features:browser")))
    // kotlin-logging needs an SLF4J binding at runtime; the app ships logback, the tests need one too.
    testRuntimeOnly(libs.logback.classic)
}
