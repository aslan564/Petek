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

package az.petek.agent.testing

import az.petek.browser.domain.LookArea
import az.petek.browser.domain.LookShot
import az.petek.browser.domain.LookShotKind
import az.petek.browser.domain.PageAnchor
import az.petek.browser.domain.PageLook
import az.petek.browser.domain.Viewport

/** Page looks as a browser would give them (`site_health`'s `look`); each frame's bytes name its page and kind. */
object Looks {
    fun shot(
        page: String,
        kind: LookShotKind = LookShotKind.MAIN,
        width: Int = 375,
        height: Int = 2_310,
        areas: List<LookArea> = emptyList(),
    ): LookShot = LookShot("visual:$page:${kind.name}".toByteArray(), kind, width, height, areas)

    fun look(
        page: String,
        vararg kinds: LookShotKind = arrayOf(LookShotKind.MAIN),
        areas: List<LookArea> = emptyList(),
        anchors: List<PageAnchor> = emptyList(),
        landedPath: String = page,
        status: Int? = 200,
        settled: Boolean = true,
        unsettled: List<String> = emptyList(),
        rejectedSelectors: List<String> = emptyList(),
    ): PageLook =
        PageLook(
            shots = kinds.map { shot(page, it, areas = areas) },
            viewport = Viewport(375, 812),
            pageHeight = 2_310,
            landedPath = landedPath,
            status = status,
            renderer = "chromium 141.0; Test OS x64; headless",
            settled = settled,
            unsettled = unsettled,
            fonts = listOf("Inter 400 normal"),
            anchors = anchors,
            rejectedSelectors = rejectedSelectors,
        )
}
