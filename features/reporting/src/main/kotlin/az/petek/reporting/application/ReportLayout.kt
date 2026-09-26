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
import az.petek.evidence.domain.ArtifactStore
import java.nio.file.Path

/**
 * Where a run's report lives: `<runDir>/report/`, next to the evidence it links to, so the report directory and
 * the run's artifacts can be moved or zipped together and every link stays a short relative path.
 */
object ReportLayout {
    const val DIRECTORY = "report"

    fun directory(
        artifacts: ArtifactStore,
        runId: RunId,
    ): Path = artifacts.runDirectory(runId).resolve(DIRECTORY)
}
