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

package az.petek.agent.application.runs

import az.petek.browser.domain.BrowserActionException
import az.petek.browser.domain.Viewport

/**
 * The screen a page-check function shows the pages on: a [Device] of its job, and back to the session's own screen
 * when the function is done, so later steps see the site as before.
 */
internal class Screens(
    private val trace: RunTrace,
) {
    private var own: Viewport? = null
    private var current: Device? = null

    suspend fun show(device: Device?) {
        if (device == null || device == current) return
        val before =
            trace.act("show the pages as a ${device.key} (${device.width}x${device.height})") {
                trace.runtime.session.resizeViewport(device.width, device.height)
            }
        if (own == null) own = before
        current = device
    }

    /** Back to the session's own screen; a session that cannot be resized any more is left as it is. */
    suspend fun restore() {
        val screen = own ?: return
        try {
            trace.runtime.session.resizeViewport(screen.width, screen.height)
        } catch (_: BrowserActionException) {
            // The session broke; the step already says why.
        }
        current = null
    }
}
