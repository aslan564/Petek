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
