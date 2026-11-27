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

package az.petek.app.init

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** How the project's AI coding agent starts `petek mcp`, from how this Pətək was started. */
class McpLaunchTest {
    private val npm = mapOf(McpLaunch.LAUNCHER to "npm@0.3.1")

    @Test
    fun `petek on PATH is written as is, the same on every machine`() {
        McpLaunch.of(npm, "/opt/petek-0.3.1") { true } shouldBe McpLaunch.ON_PATH
    }

    @Test
    fun `started by the npm launcher, the agent starts it with npx at the same version`() {
        val launch = McpLaunch.of(npm, "/home/owner/.petek/versions/0.3.1/petek") { false }

        launch.command shouldBe "npx"
        launch.args shouldContainExactly listOf("-y", "petek@0.3.1", "mcp")
    }

    @Test
    fun `a bundle outside PATH is started by its own launcher, and the owner is told the path is this machine's`() {
        val launch = McpLaunch.of(emptyMap(), "/opt/petek-0.3.1") { false }

        val name = if (System.getProperty("os.name").startsWith("Windows")) "petek.cmd" else "petek"
        launch.command shouldBe
            Path
                .of("/opt/petek-0.3.1", "bin", name)
                .toAbsolutePath()
                .normalize()
                .toString()
        launch.args shouldContainExactly listOf("mcp")
        launch.note shouldContain "this machine"
    }

    @Test
    fun `run from source nothing tells how, so petek is written`() {
        McpLaunch.of(emptyMap(), null) { false } shouldBe McpLaunch.ON_PATH
    }

    @Test
    fun `PATH is searched for an executable petek`(
        @TempDir dir: Path,
    ) {
        val bin = Files.createDirectories(dir.resolve("bin"))
        val petek = Files.createFile(bin.resolve("petek"))
        val path = listOf(dir.resolve("empty").toString(), bin.toString()).joinToString(java.io.File.pathSeparator)

        McpLaunch.onPath("petek", path) shouldBe false
        Files.setPosixFilePermissions(petek, PosixFilePermissions.fromString("rwxr-xr-x"))
        McpLaunch.onPath("petek", path) shouldBe true
        McpLaunch.onPath("petek", null) shouldBe false
    }
}
