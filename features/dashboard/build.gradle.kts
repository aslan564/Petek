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

// A simulated, lively run for looking at the dashboard by hand: ./gradlew :features:dashboard:dashboardDemo
val dashboardDemo by tasks.registering(JavaExec::class) {
    description = "Serves the live dashboard for a simulated run on http://127.0.0.1:7070 (args: --agents N --port P)."
    group = "application"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("az.petek.dashboard.demo.DashboardDemoKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    standardInput = System.`in`
}
