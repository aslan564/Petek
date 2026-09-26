/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
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

// The browser suite in one command: the panel end to end against the fake target (app), the e2e module's own runs,
// and the tester isolation proof with real Chromium contexts (browser). Each module keeps these out of its fast
// `build` (Kover would otherwise pull them in); `./gradlew e2eTest` and CI's e2e job run them here.
tasks.register("e2eTest") {
    group = "verification"
    description = "Every end-to-end run that needs a real Chromium: panel, e2e module, tester isolation."
    dependsOn(":app:e2eTest", ":e2e:e2eTest", ":features:browser:isolationTest")
}
