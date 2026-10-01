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

package az.petek.reporting.application

import az.petek.core.ids.RunId
import az.petek.core.ids.StepId
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.ArtifactStore
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.testing.InMemoryArtifactStore
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ReportLayoutTest {
    @TempDir
    lateinit var dir: Path

    private val base = RunId("run_base")
    private val now = RunId("run_now")

    /** A store that cannot resolve any record, so links come from the layout rule alone. */
    private class Unresolving(
        private val inner: ArtifactStore,
    ) : ArtifactStore by inner {
        override fun resolve(record: ArtifactRecord): Path = throw IllegalArgumentException("not resolvable")
    }

    @Test
    fun `another run's file is linked through that run's directory`() =
        runTest {
            val store = InMemoryArtifactStore(dir)
            val record = store.write(base, StepId("stp_1"), "a01", ArtifactType.VISUAL, byteArrayOf(1))

            ReportLayout.link(store, now, record) shouldBe "../../run_base/a01/0001-visual.png"
            ReportLayout.link(store, base, record) shouldBe "../a01/0001-visual.png"
            ReportLayout.visualDirectory(store, now, base) shouldBe dir.resolve("run_now/report/visual/run_base")
        }

    @Test
    fun `the layout rule links another run's file and nothing outside a run's directory`() =
        runTest {
            val store = Unresolving(InMemoryArtifactStore(dir))
            val record = store.write(base, StepId("stp_1"), "a01", ArtifactType.VISUAL, byteArrayOf(1))

            ReportLayout.link(store, now, record) shouldBe "../../run_base/a01/0001-visual.png"
            ReportLayout.link(store, now, record.copy(relativePath = "run_other/a01/0001-visual.png")).shouldBeNull()
            ReportLayout.link(store, now, record.copy(relativePath = "run_base/../etc/passwd")).shouldBeNull()
            ReportLayout.link(store, now, record.copy(runId = RunId(".."), relativePath = "../a01/x.png")).shouldBeNull()
        }
}
