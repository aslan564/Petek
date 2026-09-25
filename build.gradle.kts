/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

plugins {
    id("petek.root")
}

val moduleProjects = subprojects.filter { it.buildFile.exists() }

dependencies {
    moduleProjects.filter { it.path != ":e2e" }.forEach { kover(project(it.path)) }
}

// IntelliJ's "Build Project" runs `:classes :testClasses` on the root project. The root has no sources, so these
// lifecycle tasks aggregate the same tasks of every module instead of failing with "task not found".
listOf("classes", "testClasses").forEach { lifecycle ->
    tasks.register(lifecycle) {
        group = "build"
        description = "Runs '$lifecycle' of every module."
        dependsOn(moduleProjects.map { "${it.path}:$lifecycle" })
    }
}
