/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
