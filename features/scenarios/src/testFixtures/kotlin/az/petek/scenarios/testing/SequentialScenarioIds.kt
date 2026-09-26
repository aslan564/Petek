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

package az.petek.scenarios.testing

import az.petek.scenarios.domain.ScenarioIdGenerator
import az.petek.scenarios.domain.ScenarioVersionId
import java.util.concurrent.atomic.AtomicInteger

/** Predictable version ids for tests: `scn_1`, `scn_2`, ... */
class SequentialScenarioIds : ScenarioIdGenerator {
    private val counter = AtomicInteger(0)

    override fun versionId(): ScenarioVersionId = ScenarioVersionId("scn_${counter.incrementAndGet()}")
}
