plugins {
    id("petek.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(project(":core:domain"))
    api(project(":features:evidence"))
    api(project(":features:identity"))
    api(project(":features:orchestration"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.sse)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlin.logging)
    testImplementation(testFixtures(project(":core:domain")))
    testImplementation(testFixtures(project(":features:evidence")))
    testImplementation(testFixtures(project(":features:identity")))
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.playwright)
    testRuntimeOnly(libs.logback.classic)
}

// The whole panel with simulated data, for looking at it by hand: ./gradlew :features:dashboard:panelDemo
val panelDemo by tasks.registering(JavaExec::class) {
    description = "Serves the Pətək panel with a simulated backend on http://127.0.0.1:7070 (args: --port P --agents N --no-run)."
    group = "application"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("az.petek.dashboard.demo.PanelDemoKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    standardInput = System.`in`
}
