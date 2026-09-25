plugins {
    id("petek.kotlin-jvm")
}

dependencies {
    api(project(":core:domain"))
    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    implementation(libs.sqlite.jdbc)
}
