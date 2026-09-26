/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

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
    ":features:ownership",
    ":features:orchestration",
    ":features:reporting",
    ":features:capacity",
    ":features:scenarios",
    ":features:dashboard",
    ":features:explorer",
    ":app",
    ":testing:fake-target",
    ":e2e",
)
