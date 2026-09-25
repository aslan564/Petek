pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "petek"

include(
    ":core:domain",
    ":core:sqlite",
    ":features:campaign",
    ":features:identity",
    ":features:evidence",
    ":features:mail",
    ":features:oracle",
    ":features:browser",
    ":features:llm",
    ":features:agent",
    ":features:verification",
    ":features:orchestration",
    ":features:reporting",
    ":features:explorer",
    ":app",
    ":testing:fake-target",
    ":e2e",
)
