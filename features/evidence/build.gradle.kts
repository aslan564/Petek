plugins {
    id("petek.kotlin-jvm")
    `java-test-fixtures`
}

dependencies {
    api(project(":core:domain"))
    implementation(project(":core:sqlite"))
    testImplementation(testFixtures(project(":core:domain")))
}
