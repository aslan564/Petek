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

package az.petek.evidence.infrastructure

import az.petek.core.ids.RunId
import az.petek.evidence.domain.ArtifactType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ArtifactPathsTest {
    @Test
    fun `owner names keep only letters, digits, underscore and dash`() {
        ArtifactPaths.sanitizeOwner("a07") shouldBe "a07"
        ArtifactPaths.sanitizeOwner("orchestrator-main_1") shouldBe "orchestrator-main_1"
        ArtifactPaths.sanitizeOwner("..") shouldBe "__"
        ArtifactPaths.sanitizeOwner("../../etc/passwd") shouldBe "______etc_passwd"
        ArtifactPaths.sanitizeOwner("C:\\Windows") shouldBe "C__Windows"
        ArtifactPaths.sanitizeOwner("Əli Kərimov") shouldBe "_li_K_rimov"
        ArtifactPaths.sanitizeOwner("a\u0000b") shouldBe "a_b"
    }

    @Test
    fun `blank owners get a placeholder and long owners are truncated`() {
        ArtifactPaths.sanitizeOwner("") shouldBe "_"
        ArtifactPaths.sanitizeOwner("x".repeat(500)) shouldBe "x".repeat(ArtifactPaths.MAX_OWNER_LENGTH)
    }

    @Test
    fun `run ids must be one visible directory name`() {
        listOf("run_0192f0c4a1b27c3d8e9f00112233aabb", "run_1", "run-2.retry", "R1").forEach {
            ArtifactPaths.requireSafeRunId(RunId(it)) shouldBe it
        }
        listOf(".", "..", ".git", "../x", "a/b", "a\\b", "run 1", "-rf", "_x").forEach {
            shouldThrow<IllegalArgumentException> { ArtifactPaths.requireSafeRunId(RunId(it)) }
        }
    }

    @Test
    fun `file names carry a four digit sequence, the type and its extension`() {
        ArtifactPaths.fileName(3, ArtifactType.SCREENSHOT) shouldBe "0003-screenshot.png"
        ArtifactPaths.fileName(42, ArtifactType.A11Y) shouldBe "0042-a11y.yaml"
        ArtifactPaths.fileName(12345, ArtifactType.LOG) shouldBe "12345-log.txt"
    }

    @Test
    fun `an http_status call is stored as plain text and an oracle answer as JSON`() {
        ArtifactPaths.fileName(4, ArtifactType.HTTP) shouldBe "0004-http.txt"
        ArtifactPaths.fileName(6, ArtifactType.ORACLE) shouldBe "0006-oracle.json"
    }

    @Test
    fun `the sequence is read back only from names that start with it`() {
        ArtifactPaths.seqOf("0003-screenshot.png") shouldBe 3
        ArtifactPaths.seqOf("12345-log.txt") shouldBe 12345
        ArtifactPaths.seqOf(".0003-screenshot.png.tmp") shouldBe null
        ArtifactPaths.seqOf("notes.txt") shouldBe null
        ArtifactPaths.seqOf("99999999999-log.txt") shouldBe null
    }

    @Test
    fun `relative paths always use forward slashes`() {
        ArtifactPaths.relativePath("run_1", "a07", "0001-dom.html") shouldBe "run_1/a07/0001-dom.html"
    }

    @Test
    fun `resolving keeps paths strictly inside the root`() {
        val root = Path.of("/srv/evidence")

        ArtifactPaths.resolveInside(root, "run_1/a07/0001-dom.html") shouldBe Path.of("/srv/evidence/run_1/a07/0001-dom.html")
        listOf("..", "../x", "run_1/../../x", "", "/etc/passwd", "/srv/evidence/run_1/a07/x").forEach {
            shouldThrow<IllegalArgumentException> { ArtifactPaths.resolveInside(root, it) }
        }
    }
}
