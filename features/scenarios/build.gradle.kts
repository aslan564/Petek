plugins {
    id("petek.kotlin-jvm")
    `java-test-fixtures`
}

dependencies {
    api(project(":core:domain"))
    api(project(":features:campaign"))
    api(project(":features:evidence"))
    api(project(":features:llm"))
    implementation(project(":core:sqlite"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlin.logging)
    testFixturesImplementation(libs.kotlinx.coroutines.core)
    testImplementation(testFixtures(project(":core:domain")))
    testImplementation(testFixtures(project(":features:evidence")))
    testImplementation(testFixtures(project(":features:llm")))
}
