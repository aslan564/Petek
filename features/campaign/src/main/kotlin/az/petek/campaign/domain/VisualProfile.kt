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

package az.petek.campaign.domain

/**
 * How the site's looks are compared between releases (`target_profile.visual`, `site_health`'s `look`, docs/adr/0014).
 * [mask] names the parts of every page that are not compared because they change by themselves (a server clock, a news
 * ticker): selector references, each a key of `target_profile.selectors` or a plain CSS selector. They are found with
 * `document.querySelectorAll`, so never a Playwright-only selector ([CssSelectors]); the areas are only measured and
 * recorded, never painted on the page. This belongs in the owner's own profile only (AGENTS.md rule 13).
 */
data class VisualProfile(
    val mask: List<String> = emptyList(),
) {
    companion object {
        /** The most masks a profile may name. */
        const val MAX_MASKS = 50

        val NONE = VisualProfile()
    }
}
