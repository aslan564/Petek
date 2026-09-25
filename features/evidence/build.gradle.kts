plugins {
    id("petek.kotlin-jvm")
    `java-test-fixtures`
}

dependencies {
    api(project(":core:domain"))
    implementation(project(":core:sqlite"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(testFixtures(project(":core:domain")))
}
