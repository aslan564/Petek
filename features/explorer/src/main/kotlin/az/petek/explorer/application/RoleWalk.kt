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

package az.petek.explorer.application

import az.petek.browser.domain.BrowserSession
import az.petek.explorer.domain.SiteModel
import az.petek.explorer.domain.TestApiProbe
import az.petek.explorer.domain.TestTargetCheck

/**
 * The logged-in side of an exploration: the [sessions] the explorer walks as each role, the e-mail address each is
 * signed in with ([accounts], by role, for asking the test API about what the trial touch creates, [TestApiProbe]) and
 * the check that confirms the site's data as test data before the trial touch writes ([testCheck]; null: the use
 * case's own). The caller owns the sessions and closes them.
 */
class RoleWalk(
    val sessions: Map<String, BrowserSession> = emptyMap(),
    val accounts: Map<String, String> = emptyMap(),
    val testCheck: TestTargetCheck? = null,
) {
    companion object {
        val NONE = RoleWalk()
    }
}

/**
 * Opens the [RoleWalk] once the visitor's walk is done, with the site as it [seen][open] it (Faza 25.1), so the way in
 * can follow the site: a test company only where the site shows a way into one, never because a test API is there.
 * Called at most once per exploration, and only when a phase needs logged-in sessions.
 */
fun interface RoleWalkSource {
    suspend fun open(seen: SiteModel): RoleWalk

    companion object {
        val NONE = RoleWalkSource { RoleWalk.NONE }
    }
}
