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

package az.petek.browser.infrastructure

import az.petek.browser.domain.AlternateFact
import az.petek.browser.domain.ImageFact
import az.petek.browser.domain.LinkFact
import az.petek.browser.domain.PageFacts

/** Reads what `page-facts.js` returned into [PageFacts]; texts pass through [redact], anything malformed is left out. */
internal object PageFactsReading {
    fun of(
        raw: Map<*, *>,
        redact: (String) -> String,
    ): PageFacts =
        PageFacts(
            title = redact(raw["title"] as? String ?: ""),
            headings = (raw["headings"] as? List<*>).orEmpty().filterIsInstance<String>().map(redact),
            description = (raw["description"] as? String)?.let(redact),
            language = raw["language"] as? String,
            images =
                (raw["images"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { image ->
                    val src = image["src"] as? String ?: return@mapNotNull null
                    ImageFact(src, (image["alt"] as? String)?.let(redact), image["loaded"] as? Boolean ?: true)
                },
            links =
                (raw["links"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { link ->
                    val url = link["url"] as? String ?: return@mapNotNull null
                    LinkFact(redact(link["text"] as? String ?: ""), url)
                },
            missingAnchors = (raw["missingAnchors"] as? List<*>).orEmpty().filterIsInstance<String>(),
            alternates =
                (raw["alternates"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { alternate ->
                    val language = alternate["language"] as? String ?: return@mapNotNull null
                    val url = alternate["url"] as? String ?: return@mapNotNull null
                    AlternateFact(language, url)
                },
        )
}
