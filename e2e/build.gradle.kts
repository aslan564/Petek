plugins {
    id("petek.kotlin-jvm")
}

dependencies {
    testImplementation(project(":app"))
    testImplementation(project(":testing:fake-target"))
    testImplementation(project(":core:sqlite"))
    testImplementation(project(":features:orchestration"))
    testImplementation(project(":features:reporting"))
    testImplementation(testFixtures(project(":core:domain")))
    testImplementation(testFixtures(project(":features:llm")))
    testImplementation(libs.konsist)
    testImplementation(libs.playwright)
}

// Architecture tests run with the normal build; browser end-to-end runs are opt-in (heavier).
tasks.register<Test>("e2eTest") {
    description = "End-to-end runs against the fake target with a real Chromium."
    group = "verification"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("e2e") }
    maxHeapSize = "3g"
    shouldRunAfter(tasks.test)
}

tasks.register<Test>("liveTest") {
    description = "Opt-in runs that call the real LLM provider (uses your Claude plan/quota)."
    group = "verification"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("live") }
    maxHeapSize = "3g"
}
