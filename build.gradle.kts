plugins {
    id("petek.root")
}

dependencies {
    subprojects.filter { it.buildFile.exists() && it.path != ":e2e" }.forEach { kover(it) }
}
