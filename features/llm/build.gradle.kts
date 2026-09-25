plugins {
    id("petek.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    `java-test-fixtures`
}

dependencies {
    api(project(":core:domain"))
    api(libs.kotlinx.serialization.json)
    implementation(libs.anthropic.java)
    implementation(libs.kotlin.logging)
    testImplementation(testFixtures(project(":core:domain")))
}
