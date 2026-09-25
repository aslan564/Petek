plugins {
    id("petek.kotlin-jvm")
    application
}

dependencies {
    implementation(project(":core:sqlite"))
    implementation(project(":features:campaign"))
    implementation(project(":features:identity"))
    implementation(project(":features:evidence"))
    implementation(project(":features:mail"))
    implementation(project(":features:oracle"))
    implementation(project(":features:browser"))
    implementation(project(":features:llm"))
    implementation(project(":features:agent"))
    implementation(project(":features:verification"))
    implementation(project(":features:orchestration"))
    implementation(project(":features:reporting"))
    implementation(libs.clikt)
    implementation(libs.mordant)
    implementation(libs.kotlin.logging)
    runtimeOnly(libs.logback.classic)
    testImplementation(testFixtures(project(":core:domain")))
}

application {
    mainClass.set("az.petek.app.MainKt")
    applicationName = "petek"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}
